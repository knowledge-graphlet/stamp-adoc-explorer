/**
 * stamp-adoc-core — the Level-2 (history-walk) STAMP extraction engine over
 * JGit, shared by the IntelliJ gutter and the Komet plugin, with the IKE-dialect parser.
 *
 * @see <a href="https://github.com/IKE-Network/ike-issues/issues/468">IKE-Network/ike-issues#468</a>
 */
module stamp.adoc.core {
    requires dev.ikm.jpms.jgit;
    // StampRecord exposes RoaringBitmap line sets, so consumers read the type through this module.
    requires transitive roaringbitmap;

    exports design.knowledge.stamp.adoc.model;
    exports design.knowledge.stamp.adoc.git;
    exports design.knowledge.stamp.adoc.identity;
    exports design.knowledge.stamp.adoc.cli;
    exports design.knowledge.stamp.adoc.dialect;
}
