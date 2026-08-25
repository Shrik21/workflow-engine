package com.orchpilot.workflow.externalform;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The customer-facing form API — no OrchPilot account, no JWT, only a secure link.
 *
 * <h2>Authorised by the token, and nothing else</h2>
 *
 * Every method takes the token from the path, and every other fact — which task, which instance, which tenant —
 * is resolved from that token server-side. The customer supplies field values by name and never anything that
 * identifies the workflow, so there is nothing in the request to tamper with. These endpoints are whitelisted
 * from JWT authentication in {@code SecurityConfig}; the token is the credential, verified inside each call.
 */
@RestController
@RequestMapping("/api/public/forms")
@Tag(name = "Public forms", description = "Open, draft and submit a form via a secure external link")
public class PublicFormController {

    private final ExternalFormService externalForms;

    public PublicFormController(ExternalFormService externalForms) {
        this.externalForms = externalForms;
    }

    /** A submission or draft: field values keyed by field name, plus an optional CAPTCHA token. */
    public record PublicFormSubmission(Map<String, Object> data, String captchaToken) {
        Map<String, Object> safeData() {
            return data == null ? Map.of() : data;
        }
    }

    /** Acknowledges a saved draft without echoing the values back. */
    public record DraftAck(boolean saved) {
    }

    @GetMapping("/{token}")
    @Operation(summary = "Open a form by its secure link",
            description = "Returns only what the customer needs: the title, the fields, the expiry and what "
                    + "they may do. Never any workflow, task, tenant or form id.")
    public PublicFormView open(@PathVariable String token, HttpServletRequest request) {
        return externalForms.open(token, clientIp(request), userAgent(request));
    }

    @PostMapping("/{token}/draft")
    @Operation(summary = "Save a draft",
            description = "Stores partial input against the link's token so a returning customer sees it "
                    + "restored. Advances no workflow and writes no variable.")
    public DraftAck saveDraft(@PathVariable String token, @RequestBody PublicFormSubmission submission,
                              HttpServletRequest request) {
        externalForms.saveDraft(token, submission.safeData(), clientIp(request), userAgent(request));
        return new DraftAck(true);
    }

    @PostMapping("/{token}/submit")
    @Operation(summary = "Submit the form",
            description = "Validates on the server, completes the task and continues the workflow. Refused with "
                    + "409 when the instance is paused or terminated, or 409 when already submitted.")
    public ExternalFormService.SubmitResult submit(@PathVariable String token,
                                                   @RequestBody PublicFormSubmission submission,
                                                   HttpServletRequest request) {
        return externalForms.submit(token, submission.safeData(), clientIp(request), userAgent(request));
    }

    /** The caller's IP, preferring the first hop in {@code X-Forwarded-For} when behind the shipped proxy. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
