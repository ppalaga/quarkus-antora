package io.quarkiverse.antorassured;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector.SelectorParseException;

import com.fasterxml.jackson.databind.ObjectMapper;

public interface FragmentValidator {

    static FragmentValidator alwaysValid() {
        return (link, response) -> ValidationResult.valid(link, response.statusCode());
    }

    static FragmentValidator defaultFragmentValidator() {
        return DefaultFragmentValidator.INSTANCE;
    }

    static FragmentValidator githubBlobFragmentValidator() {
        return new GitHubBlobFragmentValidator();
    }

    static class GitHubBlobFragmentValidator implements FragmentValidator {
        private static final Pattern LINE_PATTERN = Pattern.compile("\\#L([0-9]+)");
        private static final Pattern LINES_PATTERN = Pattern.compile("\\#L([0-9]+)-L([0-9]+)");

        record TextDocument(int lastLineNumber) {
            public boolean hasLine(int lineNumber) {
                return lineNumber > 0 && lineNumber <= lastLineNumber;
            }
            public boolean hasInterval(int startLineNumber, int endLineNumber) {
                return startLineNumber > 0 && startLineNumber <= endLineNumber && endLineNumber <= lastLineNumber;
            }
        }

        @Override
        public ValidationResult validate(Link link, Response response) {
            final String fragment = link.fragment();
            /* No fragment */
            if (fragment == null) {
                return ValidationResult.valid(link, response.statusCode());
            }

            final TextDocument text = response.bodyAs(TextDocument.class, resp -> {
                final Map<String, Object> json = new ObjectMapper().readValue(resp.body(), Map.class);
                int lastLineNumber = 1;
                try (Reader r =
                        new java.io.InputStreamReader(
                                new ByteArrayInputStream(Base64.getDecoder().decode((String)json.get("content"))),
                                StandardCharsets.UTF_8)) {
                    int ch;
                    while ((ch = r.read()) >= 0) {
                        if (ch == '\n') {
                            lastLineNumber++;
                        }
                    }
                }
                return new TextDocument(lastLineNumber);
            });
            final Matcher m = LINE_PATTERN.matcher(fragment);
            if (m.matches()) {
                if (text.hasLine(Integer.parseInt(m.group(1)))) {
                    return ValidationResult.valid(link, response.statusCode());
                } else {
                    return ValidationResult.invalid(link, response.statusCode(), "Fragment " + fragment + " not found");
                }
            }
            final Matcher mm = LINES_PATTERN.matcher(fragment);
            if (mm.matches()) {
                if (text.hasInterval(Integer.parseInt(mm.group(1)), Integer.parseInt(mm.group(2)))) {
                    return ValidationResult.valid(link, response.statusCode());
                } else {
                    return ValidationResult.invalid(link, response.statusCode(), "Fragment " + fragment + " not found");
                }
            }
            return ValidationResult.invalid(link, response.statusCode(), "Fragment " + fragment + " not supported");
        }

    }
    static class DefaultFragmentValidator implements FragmentValidator {
        private static final Logger log = Logger.getLogger(AntorAssured.class);
        private static final FragmentValidator INSTANCE = new DefaultFragmentValidator();
        private static final Pattern JAVADOC_FRAGMENT_CHARS = Pattern.compile("[\\(\\)\\,\\.]");

        @Override
        public ValidationResult validate(Link link, Response response) {
            final String fragment = link.fragment();
            /* No fragment */
            if (fragment == null) {
                return ValidationResult.valid(link, response.statusCode());
            }

            /* Find the fragment */
            final Document doc = response.bodyAsHtmlDocument();

            if (JAVADOC_FRAGMENT_CHARS.matcher(fragment).find()) {
                /* Those chars are illegal in CSS selectors, so Tagsoup will fail at parsing the selector */
                final String id = fragment.substring(1);
                if (doc.getElementById(id) != null) {
                    return ValidationResult.valid(link, response.statusCode());
                }
            }

            try {
                Elements foundElems = doc.select(fragment);
                if (foundElems.isEmpty()) {
                    foundElems = doc.select("a[name=\"" + fragment.substring(1) + "\"]");
                }
                if (!foundElems.isEmpty()) {
                    return ValidationResult.valid(link, response.statusCode());
                } else {
                    return ValidationResult.invalid(link, response.statusCode(),
                            "Could not find " + fragment);
                }
            } catch (SelectorParseException e) {
                log.error("Bad fragment: " + fragment + " in URI " + link.originalUri(), e);
                throw e;
            }
        };
    }

    ValidationResult validate(Link link, Response response);
}
