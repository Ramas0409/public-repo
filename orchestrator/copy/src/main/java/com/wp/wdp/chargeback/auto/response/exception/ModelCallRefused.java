package com.wp.wdp.chargeback.auto.response.exception;

import java.util.List;

/**
 * A replay-only read that would have needed the model: the llm refused these keys, so the instrument refuses the run
 * (409). The message names the document by its place in the case ({@code doc=issuer#1}); the 409 answer names it by
 * its key, as it always has.
 */
public final class ModelCallRefused extends ConflictException {
    private final String documentRef;
    private final List<String> keys;

    public ModelCallRefused(String documentRef, String documentName, List<String> keys) {
        super(text(keys, "doc=" + documentName), documentRef);
        this.documentRef = documentRef;
        this.keys = List.copyOf(keys);
    }

    /** The 409 answer's message, which names the document by its key. */
    public String responseMessage() {
        return text(keys, documentRef);
    }

    private static String text(List<String> keys, String document) {
        return "replay-only: " + keys.size() + " key(s) of " + document + " would have been asked of the model: "
                + String.join(", ", keys);
    }

    public String documentRef() {
        return documentRef;
    }

    public List<String> keys() {
        return keys;
    }
}
