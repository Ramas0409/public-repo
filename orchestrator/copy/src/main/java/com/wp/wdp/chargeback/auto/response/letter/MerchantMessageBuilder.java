package com.wp.wdp.chargeback.auto.response.letter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wp.wdp.chargeback.auto.response.letter.model.MerchantMessage;
import com.wp.wdp.chargeback.auto.response.letter.model.SummaryAsk;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.model.decision.UndecidedKind;
import com.wp.wdp.chargeback.auto.response.service.decision.ClassifierDecision;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

/**
 * The message asking the merchant for the summary's documents, worded from {@code filing/merchant-message.json}. A
 * model sentence carrying a masked token is dropped and counted; no card number, name or token reaches the message.
 */
public final class MerchantMessageBuilder {
    public static final String WORDING = "filing/merchant-message.json";
    private static final List<String> TOKENS = List.of("[NAME_", "[CARD", "[CVV");

    private MerchantMessageBuilder() {
    }

    /** The message's wording, read from the classpath. */
    public static JsonNode wording(ObjectMapper objectMapper) {
        try (InputStream in = MerchantMessageBuilder.class.getClassLoader().getResourceAsStream(WORDING)) {
            if (in == null) {
                throw new ServiceStateException(WORDING + " is not on the classpath");
            }
            return objectMapper.readTree(in);
        } catch (IOException exception) {
            throw ServiceStateException.appending(WORDING + " could not be read", exception.getMessage(), exception);
        }
    }

    /** Why the summary asks: to strengthen a moderate contest, for evidence to contest at all, or to settle the case. */
    public static String reason(Decision decision, UndecidedKind kind) {
        if (decision != null && ClassifierDecision.CONTEST_MODERATE.equals(decision.getDecision())) {
            return MerchantMessage.STRENGTHEN;
        }
        if (decision != null && ClassifierDecision.NO_CONTEST.equals(decision.getDecision())) {
            return MerchantMessage.MISSING_EVIDENCE;
        }
        return kind == UndecidedKind.UNSETTLED ? MerchantMessage.SETTLE_UNDECIDED : null;
    }

    /**
     * The message for the asks, or null where there are none. {@code merchantClaim} and {@code gapSentence} are the
     * model's sentences, null until it writes them; {@code unreadable} chooses the clearer-copy subject.
     */
    public static MerchantMessage build(JsonNode wording, CaseRow row, String reason, boolean unreadable,
                                        List<SummaryAsk> asks, String merchantClaim, String gapSentence,
                                        String signature) {
        if (reason == null || asks == null || asks.isEmpty()) {
            return null;
        }
        JsonNode lines = lines(wording, row);
        JsonNode block = wording.path("reasons").path(reason);
        MerchantMessage message = new MerchantMessage();
        message.setVersion(wording.path("version").asText());
        message.setReason(reason);

        String claim = clean(merchantClaim, message);
        String gap = clean(gapSentence, message);
        String merchant = notBlank(row.getMerchantName()) ? row.getMerchantName().strip()
                : row.getMerchantDescriptor() == null ? "" : row.getMerchantDescriptor().strip();
        String amount = DisplayFormats.money(row.getDisputedAmount(), row.getDisputeCurrency());
        String date = DisplayFormats.date(row.getTransactionDate());
        String tail = unreadable && block.hasNonNull("tailUnreadable") ? block.path("tailUnreadable").asText()
                : block.path("tail").asText();
        boolean one = asks.size() == 1;

        message.setSubject(fill(text(lines, "subject"), Map.of("amount", amount, "transactionDate", nonNull(date),
                "tail", tail)));
        message.setTo(fill(text(lines, "to"), Map.of("merchantName", merchant)));
        message.setGreeting(fill(text(lines, "greeting"), Map.of("merchantName", merchant)));
        String card = notBlank(row.getCardLastFour())
                ? fill(text(lines, "card"), Map.of("last4", row.getCardLastFour().strip())) : "";
        message.setDisputed(fill(text(lines, "disputed"), Map.of("amount", amount, "transactionDate", nonNull(date),
                "card", card, "claim", claim == null ? "" : " " + claim)));
        message.setOpener(fill(block.path("opener").asText(), Map.of("gap", gap == null ? "" : " " + gap)));
        for (SummaryAsk ask : asks) {
            message.getItems().add(new SummaryAsk(ask.getNumber(), ask.getKey(), ask.getLabel(),
                    DisplayFormats.lowerFirst(ask.getText()), ask.getWhy(), ask.getForRow()));
        }
        message.setUploadLine(text(lines, one ? "uploadOne" : "uploadMany"));
        message.setReplyByMarker(text(lines, "personReplyBy"));
        message.setSendLine(text(lines, one ? "sendOne" : "sendMany").replace(MerchantMessage.REPLY_BY,
                message.getReplyByMarker()));
        message.setConsequence(block.path("consequence").asText());
        message.setClosing(text(lines, "closing"));
        message.setSignature(signature);
        message.setIfNoReply(block.path("ifNoReply").asText());
        message.setIfNoReplyText(block.path("ifNoReplyText").asText());
        message.setBody(body(message, lines));
        return message;
    }

    /** The whole text a client sends, with {replyBy} and {uploadLink} left for it to fill. */
    static String body(MerchantMessage message, JsonNode lines) {
        List<String> items = new ArrayList<>();
        for (SummaryAsk item : message.getItems()) {
            items.add(fill(text(lines, "item"), Map.of("n", String.valueOf(item.getNumber()), "label", item.getLabel(),
                    "detail", item.getText())));
        }
        return message.getGreeting() + "\n\n" + message.getDisputed() + "\n\n" + message.getOpener() + "\n\n"
                + String.join("\n", items) + "\n\n" + message.getUploadLine() + " " + message.getConsequence()
                + "\n\n" + message.getClosing() + "\n" + nonNull(message.getSignature());
    }

    /** The shared lines, a reason code's own lines in their place where it has any. */
    private static JsonNode lines(JsonNode wording, CaseRow row) {
        JsonNode override = wording.path("reasonCodes").path(row.getNetwork() + " " + row.getReasonCode());
        if (!override.isObject()) {
            return wording;
        }
        ObjectNode merged = wording.deepCopy();
        override.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        return merged;
    }

    private static String clean(String sentence, MerchantMessage message) {
        if (!notBlank(sentence)) {
            return null;
        }
        for (String token : TOKENS) {
            if (sentence.contains(token)) {
                message.setDroppedSentences(message.getDroppedSentences() + 1);
                return null;
            }
        }
        return sentence.strip();
    }

    private static String text(JsonNode lines, String name) {
        return lines.path(name).asText();
    }

    private static String fill(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            out = out.replace("{" + value.getKey() + "}", value.getValue());
        }
        return out;
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
