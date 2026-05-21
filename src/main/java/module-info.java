/**
 * stamp-adoc-explorer — JavaFX visualization of STAMP attribution in AsciiDoc
 * files, backed by a Level-2 (history-walk) extraction engine over JGit.
 *
 * @see <a href="https://github.com/IKE-Network/ike-issues/issues/468">IKE-Network/ike-issues#468</a>
 */
module stamp.adoc.explorer {
    requires javafx.controls;
    requires javafx.graphics;
    requires jfx.incubator.richtext;
    requires org.eclipse.jgit;
    requires org.roaringbitmap;

    exports design.knowledge.stamp.adoc;
    exports design.knowledge.stamp.adoc.model;
    exports design.knowledge.stamp.adoc.git;

    // JavaFX Application launcher requires reflective access to the App class.
    opens design.knowledge.stamp.adoc to javafx.graphics;
}
