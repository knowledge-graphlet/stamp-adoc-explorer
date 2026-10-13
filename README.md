# stamp-adoc-explorer

Incubator for STAMP attribution in git (IKE-Network/ike-issues#468): every
line of a file names the commit that introduced it, read as the Status,
Time, Author, Module and Path coordinates a Tinkar version carries.

| Module | What it is |
|--------|------------|
| `core` | The engine: a first-parent history walk over JGit with rename following and `.git-blame-ignore-revs` honored, the `Affirms` trailer format, the IKE-dialect AsciiDoc parser (`dialect`), the identity adapter, and a headless `Cli`. Java 21, no UI dependencies; consumed by the IntelliJ plugin here and by the Komet plugin (`komet-claude-plugin`). |
| `idea` | IntelliJ plugin: *Annotate with STAMPs* (gutter) and *Affirm Selected Lines…* (empty signed commit with `Affirms:` trailers). Packaged as `idea/target/stamp-adoc-idea-<version>.zip`. |

```bash
./mvnw clean package                     # build the engine and the plugin, run the engine tests, package the plugin zip
java -cp core/target/classes:$(./mvnw -q -f core dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
     design.knowledge.stamp.adoc.cli.Cli <repo-root> <file>      # headless view of one file's STAMPs
```

Install the plugin through *Settings › Plugins › ⚙ › Install Plugin from
Disk…*, or unzip it into the IDE's `plugins/` directory, then restart.

`.mvn/maven.config` pins the classic dependency conflict resolver
(`-Daether.conflictResolver.impl=classic`). The path-based resolver that
Maven 4.0.0-rc-7 (Resolver 2.0.23) selects by default exhausts a 6 GB
heap within seconds on the IntelliJ platform dependency graph, which
is small (344 artifacts) but diamond-dense (2,410 duplicate edges for
342 kept nodes). With the pin the build passes under rc-5 and rc-7 alike.
