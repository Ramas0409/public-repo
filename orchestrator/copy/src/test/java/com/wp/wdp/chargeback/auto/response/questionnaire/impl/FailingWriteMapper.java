package com.wp.wdp.chargeback.auto.response.questionnaire.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A mapper that reads as the service's does and fails every write, with a quote in its message. */
public class FailingWriteMapper extends ObjectMapper {
    private static final long serialVersionUID = 1L;

    public static final String QUOTE = "Bea Example of 14 Sample Row";

    @Override
    public ObjectMapper copy() {
        return new ObjectMapper();
    }

    @Override
    public String writeValueAsString(Object value) throws JsonMappingException {
        throw JsonMappingException.from((JsonParser) null, "cannot write " + QUOTE);
    }
}
