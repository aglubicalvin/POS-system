-- Sets the probe role password. \password is a psql meta-command: psql hashes the
-- password on the client (scram-sha-256) and sends only the hash. The plaintext
-- never appears in SQL text, argv, or the server log.
-- Must be fed on stdin by the wrapper (psql reads the two prompts from stdin).
\password pos_probe_readonly
