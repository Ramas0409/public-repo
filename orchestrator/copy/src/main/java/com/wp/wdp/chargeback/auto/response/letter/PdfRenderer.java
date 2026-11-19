package com.wp.wdp.chargeback.auto.response.letter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

/** HTML to PDF for both letters, the DejaVu faces embedded and the stylesheets read from the classpath (D9). */
public final class PdfRenderer {
    private static final String FONTS = "filing/fonts/";
    private static final String SANS = "DejaVu Sans";
    private static final String MONO = "DejaVu Sans Mono";

    private PdfRenderer() {
    }

    public static byte[] render(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .useFastMode()
                    .useFont(() -> resource(FONTS + "DejaVuSans.ttf"), SANS, 400,
                            BaseRendererBuilder.FontStyle.NORMAL, true)
                    .useFont(() -> resource(FONTS + "DejaVuSans-Bold.ttf"), SANS, 700,
                            BaseRendererBuilder.FontStyle.NORMAL, true)
                    .useFont(() -> resource(FONTS + "DejaVuSansMono.ttf"), MONO, 400,
                            BaseRendererBuilder.FontStyle.NORMAL, true)
                    .withHtmlContent(html, null)
                    .toStream(out)
                    .run();
            return out.toByteArray();
        } catch (Exception exception) {
            throw ServiceStateException.appending("the letter could not be rendered to PDF",
                    exception.getMessage(), exception);
        }
    }

    /** A whole page: the title, the stylesheet's text, the body's markup. */
    public static String document(String title, String stylesheet, String bodyHtml) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>" + HtmlEscape.escape(title)
                + "</title><style>" + stylesheet + "</style></head><body>" + bodyHtml + "</body></html>";
    }

    /** The named classpath stylesheets as one text, in the order given - which is the order of the cascade. */
    public static String stylesheet(String... paths) {
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            try (InputStream in = resource(path)) {
                out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (IOException exception) {
                throw new ServiceStateException("the stylesheet " + path + " could not be read", exception);
            }
        }
        return out.toString();
    }

    private static InputStream resource(String path) {
        try {
            return new ClassPathResource(path).getInputStream();
        } catch (IOException exception) {
            throw new ServiceStateException("the letter resource " + path + " could not be read", exception);
        }
    }
}
