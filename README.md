# stamp-adoc-explorer

Incubator for STAMP attribution in git (IKE-Network/ike-issues#468): every
line of a file names the commit that introduced it, read as the Status,
Time, Author, Module and Path coordinates a Tinkar version carries.

| Module | What it is |
|--------|------------|
| `core` | The engine: a first-parent history walk over JGit with rename following and `.git-blame-ignore-revs` honored, the `Affirms` trailer format, and a headless `Cli`. Java 21, no UI dependencies. |
| `fx`   | JavaFX RichTextArea visualization (Java 25, preview features). |
| `idea` | IntelliJ plugin: *Annotate with STAMPs* (gutter) and *Affirm Selected Lines…* (empty signed commit with `Affirms:` trailers). Packaged as `idea/target/stamp-adoc-idea-<version>.zip`. |

```bash
./mvnw clean package                     # build everything, run the engine tests, package the plugin zip
java -cp core/target/classes:$(./mvnw -q -f core dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
     design.knowledge.stamp.adoc.cli.Cli <repo-root> <file>      # headless view of one file's STAMPs
```

Install the plugin through *Settings › Plugins › ⚙ › Install Plugin from
Disk…*, or unzip it into the IDE's `plugins/` directory, then restart.

Build with the wrapper: it pins Maven 4.0.0-rc-5. rc-7's conflict resolver
exhausts the heap on the IntelliJ platform dependency graph.
