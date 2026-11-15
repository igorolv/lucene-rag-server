package ru.it_spectrum.ai.rag.mcp.document;

import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;

/**
 * What of a record's {@link Document} a model sees (docs/decisions.md D44, D61). For
 * {@link MetadataMode#EMBED} it is the passage prefix of the model, the breadcrumb, a blank line
 * and the text: the breadcrumb says what a passage is about. {@link MetadataMode#INFERENCE} and
 * {@link MetadataMode#ALL} give the same without the prefix, {@link MetadataMode#NONE} the text
 * alone. The breadcrumb is the metadata entry {@link RecordDocuments#BREADCRUMB}; a document
 * without one is its text.
 *
 * @param passagePrefix put before the embedded text, {@code "passage: "} for E5; may be empty
 */
public record RecordContentFormatter(String passagePrefix) implements ContentFormatter {

    public RecordContentFormatter {
        passagePrefix = passagePrefix == null ? "" : passagePrefix;
    }

    @Override
    public String format(Document document, MetadataMode mode) {
        String text = document.getText() == null ? "" : document.getText();
        if (mode == MetadataMode.NONE) {
            return text;
        }
        Object breadcrumb = document.getMetadata().get(RecordDocuments.BREADCRUMB);
        String head = breadcrumb == null ? "" : breadcrumb.toString().strip();
        String content = head.isEmpty() ? text : head + "\n\n" + text;
        return mode == MetadataMode.EMBED ? passagePrefix + content : content;
    }

    /** The document formatted by this formatter. */
    public Document apply(Document document) {
        document.setContentFormatter(this);
        return document;
    }
}
