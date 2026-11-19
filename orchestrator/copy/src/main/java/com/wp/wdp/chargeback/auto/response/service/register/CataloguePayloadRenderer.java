package com.wp.wdp.chargeback.auto.response.service.register;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.wp.wdp.chargeback.auto.response.dto.llm.SubFormField;
import com.wp.wdp.chargeback.auto.response.model.decision.RenderedPayload;
import com.wp.wdp.chargeback.auto.response.model.register.CatalogueArgument;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRow;
import com.wp.wdp.chargeback.auto.response.questionnaire.QuestionnaireBody;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireField;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

/** The argument list, rendered. */
public final class CataloguePayloadRenderer {
    static final String NO_LINE = "(no line written for this argument)";
    static final String VALUES_LINE = "If you nominate this argument, also give each value below that the merchant's "
            + "records show, with the record you read it from. Copy each value exactly as the record prints it, except a "
            + "description, which you write in plain words from the record you quote. Where a set form is asked, give "
            + "that form as well. Include a yes-or-no item only where the records show it. "
            + "Leave out any value the records do not show; never work one out.";
    static final String DESCRIPTION = "a short description in plain words, from the quoted record";

    private CataloguePayloadRenderer() {
    }

    public static RenderedPayload render(ArgumentCatalogue catalogue, Map<String, RegisterRow> register) {
        return render(catalogue, register, catalogue.grounds(), catalogue.supplementaryCodes(), Map.of());
    }

    /** The argument list with, under each row whose form asks for values, the values it asks for. */
    public static RenderedPayload render(ArgumentCatalogue catalogue, Map<String, RegisterRow> register,
                                         Map<String, List<SubFormField>> subFormFields) {
        return render(catalogue, register, catalogue.grounds(), catalogue.supplementaryCodes(), subFormFields);
    }

    public static RenderedPayload render(ArgumentCatalogue catalogue, Map<String, RegisterRow> register,
                                  List<String> groundCodes, List<String> supplementaryCodes) {
        return render(catalogue, register, groundCodes, supplementaryCodes, Map.of());
    }

    static RenderedPayload render(ArgumentCatalogue catalogue, Map<String, RegisterRow> register,
                                  List<String> groundCodes, List<String> supplementaryCodes,
                                  Map<String, List<SubFormField>> subFormFields) {
        Map<String, List<SubFormField>> asked = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> renderedGrounds = new ArrayList<>();
        List<String> qualifiedGrounds = new ArrayList<>();
        for (String code : groundCodes) {
            RegisterRow row = register.get(code);
            if (row == null) {
                missing.add(code);
                continue;
            }
            qualifiedGrounds.add(catalogue.qualified(code));
            renderedGrounds.add(row(catalogue.qualified(code), row, catalogue.argument(code))
                    + values(catalogue.qualified(code), subFormFields, asked));
        }

        StringBuilder text = new StringBuilder().append(header(catalogue, qualifiedGrounds)).append('\n');
        if (!renderedGrounds.isEmpty()) {
            text.append('\n').append(clean(catalogue.howToRead()))
                    .append("\n\n").append(clean(catalogue.termsUsedBelow()))
                    .append("\n\n").append(String.join("\n\n", renderedGrounds)).append('\n');
        }

        List<String> renderedSupplementary = new ArrayList<>();
        List<String> qualifiedSupplementary = new ArrayList<>();
        for (String code : supplementaryCodes) {
            CatalogueArgument argument = catalogue.argument(code);
            RegisterRow row = register.get(code);
            if (row == null) {
                missing.add(code);
                continue;
            }
            qualifiedSupplementary.add(catalogue.qualified(code));
            renderedSupplementary.add(row(catalogue.qualified(code), row, argument)
                    + values(catalogue.qualified(code), subFormFields, asked));
        }
        if (!renderedSupplementary.isEmpty()) {
            text.append('\n').append(clean(catalogue.supplementaryHeader()))
                    .append("\n\n").append(String.join("\n\n", renderedSupplementary)).append('\n');
        }

        String payload = text.toString();
        return new RenderedPayload(payload, digest(payload), List.copyOf(qualifiedGrounds),
                List.copyOf(qualifiedSupplementary), List.copyOf(missing), asked);
    }

    static String header(ArgumentCatalogue catalogue, List<String> codes) {
        String joined;
        if (codes.isEmpty()) {
            joined = "none";
        } else if (codes.size() == 1) {
            joined = codes.get(0);
        } else {
            joined = String.join(", ", codes.subList(0, codes.size() - 1)) + " and "
                    + codes.get(codes.size() - 1);
        }
        return catalogue.headerTemplate().replace("{reasonCode}", catalogue.reasonCode())
                .replace("{codes}", joined);
    }

    private static String row(String code, RegisterRow row, CatalogueArgument argument) {
        String header = "[" + code + "] " + row.getKind() + " · " + row.getPassageAnchor()
                + (row.isRegional() ? " · " + row.getRegion() + " region only" : "");
        return String.join("\n", List.of(
                header,
                "Rule: " + clean(row.getRequirementText()),
                "Nominate when: " + (argument == null ? NO_LINE : clean(argument.getNominateWhen())),
                "Strong evidence: " + (argument == null ? NO_LINE : clean(argument.getStrongEvidence())),
                "Does not fit: " + (argument == null ? NO_LINE : clean(argument.getDoesNotFit()))));
    }

    private static String values(String code, Map<String, List<SubFormField>> subFormFields,
                                 Map<String, List<SubFormField>> asked) {
        List<SubFormField> fields = subFormFields.get(code);
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        asked.put(code, fields);
        StringBuilder out = new StringBuilder("\n").append(VALUES_LINE);
        for (SubFormField field : fields) {
            out.append("\n- ").append(field.getName()).append(" (").append(field.getLabel()).append("): ")
                    .append(asks(field));
        }
        return out.toString();
    }

    static String asks(SubFormField field) {
        String type = field.getType();
        String out;
        if (QuestionnaireField.BOOLEAN.equals(type)) {
            out = "true only, where the records show it";
        } else if (QuestionnaireField.CODE.equals(type)) {
            out = "one of " + String.join(", ", field.getOptions());
        } else if (QuestionnaireField.AMOUNT.equals(type)) {
            out = "a number";
        } else if (QuestionnaireField.LONG_TEXT.equals(type)) {
            out = DESCRIPTION;
        } else if (field.getFormat() != null) {
            out = field.getFormat();
        } else {
            out = field.getMaxLength() == null ? "text" : "text of at most " + field.getMaxLength() + " characters";
        }
        if (Boolean.TRUE.equals(field.getPrinted())) {
            out += ", with the text as printed";
        }
        Integer items = QuestionnaireBody.listItems(field.getName());
        if (items != null) {
            out += "; one per item, [0] to [" + (items - 1) + "]";
        }
        return out;
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    static String digest(String payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new ServiceStateException("SHA-256 is not available", exception);
        }
    }
}
