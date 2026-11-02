-- Sets the probe role password from the psql variable probe_pw.
-- Run after pos_probe_readonly.sql. The password comes from the environment,
-- never from this file. Requires psql -v probe_pw=...
alter role pos_probe_readonly password :'probe_pw';
