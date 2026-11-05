package com.businessstore.pos.tools

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/**
 * The exact certificate pgJDBC is allowed to trust, in a form only this process
 * can read.
 *
 * [ProvisionTarget.resolve] reads the caller-named file ONCE, parses that byte
 * snapshot, and pins the SHA-256 of the certificate's DER encoding. But pgJDBC's
 * `sslrootcert` is a PATH: it opens the file again when the TLS connection is
 * built. Handing it the caller's path therefore leaves a time-of-check to
 * time-of-use window -- anything that can write that file between validation and
 * connection could substitute its own CA, and `sslmode=verify-full` would then
 * accept a certificate that CA issued for the pooler hostname.
 *
 * So the verified bytes, and nothing else, are written to a NEW file that the
 * caller never named, inside a directory this process creates with owner-only
 * access. The copy is verified again after writing (digest and single
 * certificate), is deleted when the provisioning operation ends, and its path is
 * never derived from the source path.
 *
 * Platform model:
 *   - POSIX: the directory is created 0700 and the file 0600 ATOMICALLY at
 *     creation, so there is no interval in which they are group/world readable.
 *   - Windows: the inherited ACL is replaced with one that grants only the
 *     current user (plus SYSTEM and Administrators, which can read anything
 *     anyway), then read back and checked.
 *
 * If the restricted location or its permissions cannot be established, creation
 * fails closed: the caller must refuse to provision rather than fall back to the
 * caller-controlled path.
 */
internal class PinnedTrustMaterial private constructor(
    private val directory: Path,
    private val file: Path,
    /** SHA-256 of the certificate's DER encoding, as verified at resolve time. */
    val sha256: String,
) : AutoCloseable {

    /** Path pgJDBC is given; points at the private copy, never the source file. */
    val path: String get() = file.toString()

    /** Delete the copy and its directory. Safe to call more than once. */
    override fun close() {
        runCatching { Files.deleteIfExists(file) }
        runCatching { Files.deleteIfExists(directory) }
    }

    sealed class Outcome {
        class Ready(val material: PinnedTrustMaterial) : Outcome()

        /** No safe copy could be produced; provisioning must not proceed. */
        data class Unavailable(val code: String) : Outcome()
    }

    companion object {

        /**
         * Write [ProvisionTarget.Target.rootCertificatePem] to a private file and
         * prove the file holds that certificate before returning it.
         *
         * [parent] exists so tests can exercise creation and cleanup failures; the
         * production caller uses the process temporary directory.
         */
        fun create(
            target: ProvisionTarget.Target,
            parent: Path = Paths.get(System.getProperty("java.io.tmpdir")),
        ): Outcome {
            if (target.rootCertificatePem.isEmpty()) return Outcome.Unavailable("pinned-trust-integrity")

            val posix = supportsPosix(parent)
            val directory = try {
                if (posix) {
                    Files.createTempDirectory(
                        parent,
                        "pos-pinned-root",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                } else {
                    Files.createTempDirectory(parent, "pos-pinned-root")
                }
            } catch (error: IOException) {
                return Outcome.Unavailable("pinned-trust-unavailable")
            } catch (error: SecurityException) {
                return Outcome.Unavailable("pinned-trust-unavailable")
            }

            var file: Path? = null
            var handedOut = false
            try {
                if (!restrict(directory)) return Outcome.Unavailable("pinned-trust-permissions")
                file = if (posix) {
                    Files.createTempFile(
                        directory,
                        "root-ca",
                        ".crt",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(directory, "root-ca", ".crt")
                }
                if (!restrict(file)) return Outcome.Unavailable("pinned-trust-permissions")
                Files.newOutputStream(
                    file,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { it.write(target.rootCertificatePem.toByteArray(Charsets.UTF_8)) }

                // Read the copy back through the driver's own parser. If it is not
                // exactly the pinned certificate, the file on disk is not what was
                // verified and must never reach pgJDBC.
                val written = runCatching { Files.readAllBytes(file) }.getOrNull()
                    ?: return Outcome.Unavailable("pinned-trust-integrity")
                val pinned = ProvisionTarget.readRootCertificate(written)
                    as? ProvisionTarget.RootCertificate.Pinned
                    ?: return Outcome.Unavailable("pinned-trust-integrity")
                if (pinned.sha256 != target.rootCertificateSha256) {
                    return Outcome.Unavailable("pinned-trust-integrity")
                }
                if (!isRestricted(file) || !isRestricted(directory)) {
                    return Outcome.Unavailable("pinned-trust-permissions")
                }
                handedOut = true
                return Outcome.Ready(PinnedTrustMaterial(directory, file, pinned.sha256))
            } catch (error: IOException) {
                return Outcome.Unavailable("pinned-trust-unavailable")
            } catch (error: SecurityException) {
                return Outcome.Unavailable("pinned-trust-unavailable")
            } catch (error: UnsupportedOperationException) {
                return Outcome.Unavailable("pinned-trust-permissions")
            } finally {
                // Every early return above is a failure: leave nothing behind.
                if (!handedOut) {
                    runCatching { file?.let { Files.deleteIfExists(it) } }
                    runCatching { Files.deleteIfExists(directory) }
                }
            }
        }

        private fun supportsPosix(path: Path): Boolean =
            runCatching { path.fileSystem.supportedFileAttributeViews().contains("posix") }.getOrDefault(false)

        /** Apply owner-only access using the platform's permission model. */
        private fun restrict(path: Path): Boolean = try {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            if (acl != null) restrictAcl(path, acl) else restrictPosix(path)
        } catch (error: IOException) {
            false
        } catch (error: SecurityException) {
            false
        } catch (error: UnsupportedOperationException) {
            false
        }

        private fun restrictPosix(path: Path): Boolean {
            val wanted = if (Files.isDirectory(path)) "rwx------" else "rw-------"
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(wanted))
            return isRestricted(path)
        }

        /**
         * Replace the inherited ACL with one naming only the current user, SYSTEM
         * and the Administrators group. Setting the ACL drops the inherited
         * entries, so no other local account keeps a grant.
         */
        private fun restrictAcl(path: Path, acl: AclFileAttributeView): Boolean {
            val owner = acl.owner ?: return false
            val allowed = allowedPrincipals(path, owner)
            val permissions = AclEntryPermission.values().toSet()
            acl.acl = allowed.map { principal ->
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(principal)
                    .setPermissions(permissions)
                    .build()
            }
            return isRestricted(path)
        }

        /** The owner plus the two built-in accounts that bypass ACLs regardless. */
        private fun allowedPrincipals(path: Path, owner: UserPrincipal): List<UserPrincipal> {
            val lookup = runCatching { path.fileSystem.userPrincipalLookupService }.getOrNull()
            val principals = mutableListOf(owner)
            for (name in listOf("SYSTEM", "Administrators")) {
                val principal = runCatching { lookup?.lookupPrincipalByName(name) }.getOrNull()
                if (principal != null && principal !in principals) principals += principal
            }
            return principals
        }

        /** Read the restriction back; a failure here is a failure to fail closed. */
        private fun isRestricted(path: Path): Boolean = try {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            if (acl != null) {
                val owner = acl.owner ?: return false
                val allowed = allowedPrincipals(path, owner).toSet()
                acl.acl.all { it.type() == AclEntryType.ALLOW && it.principal() in allowed }
            } else {
                val wanted = if (Files.isDirectory(path)) "rwx------" else "rw-------"
                PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) == wanted
            }
        } catch (error: IOException) {
            false
        } catch (error: SecurityException) {
            false
        } catch (error: UnsupportedOperationException) {
            false
        }
    }
}
