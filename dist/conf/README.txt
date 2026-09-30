conf/ of the Cringle distribution

This directory is reserved for machine-wide settings that the installers (issues #58 and #59) write next to the
installation. Nothing in this release reads a file from here yet: the start scripts take the JVM options from the
environment variable CRINGLE_JVM_OPTS, and the Cringle home (the data of a machine) is chosen with CRINGLE_HOME or
--home, see docs/daemon-service.md, docs/management-server.md and docs/cli.md.
