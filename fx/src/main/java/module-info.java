/**
 * stamp-adoc-fx — JavaFX RichTextArea visualization of STAMP attribution.
 */
module stamp.adoc.fx {
    requires stamp.adoc.core;
    requires javafx.controls;
    requires javafx.graphics;
    requires jfx.incubator.richtext;

    // JavaFX Application launcher requires reflective access to the App class.
    opens design.knowledge.stamp.adoc.fx to javafx.graphics;
}
