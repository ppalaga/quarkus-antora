package io.quarkiverse.antorassured;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.quarkiverse.antorassured.AggregatePolicy.AggregatePolicyResult;
import io.quarkiverse.antorassured.LinkValidator.LinkValidatorImpl;

/**
 * A stream of {@link Link}s.
 *
 * @since 1.0.0
 */
public class LinkStream {
    private static final Logger log = Logger.getLogger(AntorAssured.class);
    private final Stream<Link> links;
    private final ResourceResolver resourceResolver;
    private final int retryAttempts;
    private long overallTimeout;
    private final List<LinkGroup> groups;

    LinkStream(
            Stream<Link> links,
            ResourceResolver resourceResolver,
            int retryAttempts,
            List<LinkGroup> groups,
            long overallTimeout) {
        super();
        this.links = links;
        this.resourceResolver = resourceResolver;
        this.retryAttempts = retryAttempts;
        this.groups = groups;
        this.overallTimeout = overallTimeout;
    }

    LinkStream(
            Stream<Link> links,
            ResourceResolver resourceResolver,
            int retryAttempts,
            long overallTimeout) {
        this(links, resourceResolver, retryAttempts,
                Collections.singletonList(createDefaultGroup()),
                overallTimeout);
    }

    static LinkGroup createDefaultGroup() {
        return new LinkGroup(
                null,
                Pattern.compile(".*"),
                Function.identity(),
                Collections.emptyMap(),
                RateLimit.none(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                new LinkGroupStats(),
                FragmentValidator.defaultFragmentValidator());
    }

    /**
     * @return the underlying {@link Stream}
     *
     * @since 1.0.0
     */
    public Stream<Link> stream() {
        return links;
    }

    /**
     * @return a new {@link LinkStream} that logs the current {@link Link}
     *
     * @since 1.0.0
     */
    public LinkStream log() {
        return new LinkStream(
                links.peek(link -> AntorAssured.log.info(link)),
                resourceResolver,
                retryAttempts,
                groups,
                overallTimeout);
    }

    /**
     * @return a new {@link LinkStream} that filters out the links satisfying
     *         {@link ResourceResolver#isAsciiDocSource(Link)}
     *
     * @since 1.0.0
     */
    public LinkStream excludeEditThisPage() {
        return exclude(ResourceResolver::isAsciiDocSource);
    }

    /**
     * @param exclude a {@link Predicate} whose matching {@link Link}s should be removed from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} that satisfy the given {@code exclude}
     *         {@link Predicate}
     *
     * @since 1.0.0
     */
    public LinkStream exclude(Predicate<Link> exclude) {
        return new LinkStream(
                links.filter(link -> !exclude.test(link)),
                resourceResolver,
                retryAttempts,
                groups,
                overallTimeout);
    }

    /**
     * @param exclude a {@link Pattern} applied on {@link Link#resolvedUri()}; the matching {@link Link}s will be
     *        removed from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} that satisfy the given {@code exclude}
     *         {@link Pattern}
     *
     * @since 1.0.0
     */
    public LinkStream excludeResolved(Pattern exclude) {
        return new LinkStream(
                links.filter(link -> !exclude.matcher(link.resolvedUri()).matches()),
                resourceResolver,
                retryAttempts,
                groups,
                overallTimeout);
    }

    /**
     * @param excludes absolute URIs to remove from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} having with any of the given {@code excludes} as
     *         their {@link Link#resolvedUri()}
     *
     * @since 1.0.0
     */
    public LinkStream excludeResolved(String... excludes) {
        final Set<String> set = new HashSet<>();
        for (String uri : excludes) {
            set.add(uri);
        }
        return excludeResolved(set);
    }

    /**
     * @param excludes absolute URIs to remove from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} having with any of the given {@code excludes} as
     *         their {@link Link#resolvedUri()}
     *
     * @since 1.0.0
     */
    public LinkStream excludeResolved(Collection<String> excludes) {
        return new LinkStream(
                links.filter(link -> !excludes.contains(link.resolvedUri())),
                resourceResolver,
                retryAttempts,
                groups,
                overallTimeout);
    }

    /**
     * @param include a {@link Pattern} applied on {@link Link#resolvedUri()}; only the matching {@link Link}s will be
     *        kept in this {@link LinkStream}
     * @return a new {@link LinkStream} that keeps only {@link Links} matching the given {@code include} {@link Pattern}
     *
     * @since 1.0.0
     */
    public LinkStream includeResolved(Pattern include) {
        return new LinkStream(
                links.filter(link -> include.matcher(link.resolvedUri()).matches()),
                resourceResolver,
                retryAttempts,
                groups,
                overallTimeout);
    }

    /**
     * @param retryAttempts how many times it should be re-tried to retrieve a remote link, in case it responds with
     *        HTTP
     *        301, 429, 500, 501, 502, 503 or 504. If not set the default is one retry attempt after 10 seconds or
     *        what
     *        the {@code Retry-After} HTTP header prescribes, but at most 120 seconds.
     * @return a new {@link LinkStream} with {@link #retryAttempts} reset to the given value
     *
     * @since 1.3.0
     */
    public LinkStream retryAttempts(int retryAttempts) {
        return new LinkStream(links, resourceResolver, retryAttempts, groups, overallTimeout);
    }

    /**
     * @param overallTimeout a timeout in milliseconds within which all links must get validated including any retries;
     *        if not set, the default value is 30000 milliseconds
     * @return a new {@link LinkStream} with {@link #overallTimeout} reset to the given value
     *
     * @since 1.3.0
     */
    public LinkStream overallTimeout(long overallTimeout) {
        return new LinkStream(links, resourceResolver, retryAttempts, groups, overallTimeout);
    }

    /**
     * @param regExp a regular expression, the given {@code rateLimit} will be applied to matching URI's
     * @param rateLimit a {@link RateLimit}, such as {@link RateLimit#requestsPerTimeInterval(int, long)}
     * @return a new {@link LinkStream} with {@link RateLimit} set to matching URIs.
     *
     * @since 1.4.0
     * @deprecated use {@code group(regExp).rateLimit(rateLimit).endGroup()}
     */
    public LinkStream rateLimit(String regExp, RateLimit rateLimit) {
        return group(regExp).rateLimit(rateLimit).endGroup();
    }

    /**
     * Creates a new {@link LinkGroup} for further customization. Note that the {@link LinkGroup} is not added to the
     * parent {@link LinkStream} before calling {@link LinkGroup#endGroup()}.
     *
     * There is a default {@code .*} fallback group added by default and always kept at the last position in the list of
     * {@link LinkGroup}s.
     *
     * Each {@link Link} can belongs on to the first {@link LinkGroup} whose {@link LinkGroup#pattern()} it matches.
     * The first {@link LinkGroup} added will be tested first and the last {@link LinkGroup} added will be tested last
     * (before the final default fallback {@link LinkGroup}).
     *
     * @param regExp a regular expression to select resolved links belonging to the {@link LinkGroup}
     * @return a new {@link LinkGroup} that can be further customized
     *
     * @since 2.0.0
     */
    public LinkGroup group(String regExp) {
        return new LinkGroup(
                this,
                Pattern.compile(regExp),
                Function.identity(),
                Collections.emptyMap(),
                RateLimit.none(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                new LinkGroupStats(),
                FragmentValidator.defaultFragmentValidator());
    }

    /**
     * @return a {@link ValidationErrorStream} using {@link LinkValidatorImpl}.
     *
     * @since 1.0.0
     */
    public ValidationErrorStream validate() {
        return validate(new LinkValidatorImpl());
    }

    /**
     * @param validator the {@link LinkValidator} to use for validating {@link Link}s
     * @return a new {@link ValidationErrorStream}
     *
     * @since 1.0.0
     */
    public ValidationErrorStream validate(LinkValidator validator) {
        final long deadline = System.currentTimeMillis() + overallTimeout;
        final List<ValidationResult> invalidWithouRetry = new ArrayList<>();
        final List<ValidationResult> invalidWithRetry = new ArrayList<>();
        Stream<Link> newLinks = links;

        for (LinkGroup group : groups) {
            for (Function<Stream<Link>, Stream<Link>> t : group.streamTransformers) {
                newLinks = t.apply(newLinks);
            }
        }

        newLinks
                .map(this::createRequest)
                .filter(ValidationRequest::shouldContinue)
                .map(req -> {
                    if (deadline <= System.currentTimeMillis()) {
                        return ValidationResult.invalid(req.link(), 0,
                                "Did not try, overall timeout of " + overallTimeout + " ms expired");
                    } else {
                        return validator.validate(req);
                    }
                })
                .filter(ValidationResult::isInvalid)
                .forEach(result -> {
                    if (result.shouldRetry()) {
                        synchronized (invalidWithRetry) {
                            invalidWithRetry.add(result);
                        }
                    } else {
                        synchronized (invalidWithouRetry) {
                            invalidWithouRetry.add(result);
                        }
                    }
                });

        while (!invalidWithRetry.isEmpty()) {
            /* sort the retries by retry time */
            Collections.sort(invalidWithRetry, Comparator.comparing(ValidationResult::retryAtSystemTimeMs));

            final ValidationResult oldResult = invalidWithRetry.get(0);
            /* Should we continue? */
            final ValidationRequest req = createRequest(oldResult.uri());
            if (!req.shouldContinue()) {
                invalidWithRetry.remove(0);
                continue;
            }
            /* Retry */
            final long delay = oldResult.retryAtSystemTimeMs() - System.currentTimeMillis();
            if (oldResult.retryAtSystemTimeMs() >= deadline) {
                invalidWithRetry.remove(0);
                invalidWithouRetry.add(ValidationResult.invalid(oldResult.uri(), 0,
                        "Did not try (again), overall timeout of " + overallTimeout + " ms expired"));
                continue;
            }

            if (delay > 0L) {
                try {
                    log.infof("Sleeping %d ms to retry %s; there are still %d URIs to retry ", delay, oldResult,
                            invalidWithRetry.size());
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            final ValidationResult newResult = validator.validate(req);
            if (newResult.isValid()) {
                invalidWithRetry.remove(0);
            } else if (newResult.shouldRetry()) {
                invalidWithRetry.remove(0);
                invalidWithRetry.add(newResult);
            } else {
                invalidWithRetry.remove(0);
                invalidWithouRetry.add(newResult);
            }

        }
        return new ValidationErrorStream(
                Stream.of(
                        invalidWithouRetry.stream(),
                        invalidWithRetry.stream(),
                        groups.stream()
                                .map(group -> group.applyFinalPolicies())
                                .filter(ValidationResult::isInvalid))
                        .flatMap(s -> s),
                resourceResolver);
    }

    ValidationRequest createRequest(Link link) {
        for (LinkGroup group : groups) {
            if (group.pattern.matcher(link.resolvedUri()).matches()) {
                return new ValidationRequest(group.linkMapper.apply(link), retryAttempts + 1, group);
            }
        }
        return new ValidationRequest(link, retryAttempts + 1, groups.get(groups.size() - 1));
    }

    /**
     * Statistics of a {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public static class LinkGroupStats {
        private final Map<Integer, AtomicInteger> stats = new ConcurrentHashMap<>();

        /**
         * Records the given {@code statusCode} in this {@link LinkGroupStats}.
         *
         * @param statusCode
         */
        public void recordStatus(int statusCode) {
            stats.computeIfAbsent(statusCode, k -> new AtomicInteger()).incrementAndGet();
        }

        /**
         * @param statusCode the HTTP status code whose counts should returned
         * @return the number of HTTP responses having the given {@code statusCode}
         *
         * @since 2.0.0
         */
        public int getResponseCountByStatus(int statusCode) {
            return stats.computeIfAbsent(statusCode, k -> new AtomicInteger()).get();
        }
    }

    /**
     * A group if {@link Link}s defined by a {@link Pattern} on which additional constraints and policies can be applied.
     *
     * @since 2.0.0
     */
    public static class LinkGroup {
        private final LinkStream parent;
        private final Pattern pattern;
        private final RateLimit rateLimit;
        private final List<Function<Stream<Link>, Stream<Link>>> streamTransformers;
        final List<AggregatePolicy> continuationPolicies;
        private final List<AggregatePolicy> finalPolicies;
        private final LinkGroupStats stats;
        private final Map<String, List<String>> headers;
        private final Function<Link, Link> linkMapper;
        private final FragmentValidator fragmentValidator;

        private LinkGroup(
                LinkStream parent,
                Pattern pattern,
                Function<Link, Link> linkMapper,
                Map<String, List<String>> headers,
                RateLimit rateLimit,
                List<Function<Stream<Link>, Stream<Link>>> streamTransformers,
                List<AggregatePolicy> continuationPolicies,
                List<AggregatePolicy> finalPolicies,
                LinkGroupStats stats,
                FragmentValidator fragmentValidator) {
            this.parent = parent;
            this.pattern = pattern;
            this.linkMapper = linkMapper;
            this.headers = headers;
            this.rateLimit = rateLimit;
            this.streamTransformers = streamTransformers;
            this.continuationPolicies = continuationPolicies;
            this.finalPolicies = finalPolicies;
            this.stats = stats;
            this.fragmentValidator = fragmentValidator;
        }

        /**
         * @param rateLimit the {@link RateLimit} to apply on this {@link LinkGroup}
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup rateLimit(RateLimit rateLimit) {
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    headers,
                    rateLimit,
                    streamTransformers,
                    continuationPolicies,
                    finalPolicies,
                    stats,
                    fragmentValidator);
        }

        /**
         * Shuffle the order of the Links belonging to this {@link LinkGroup}.
         * The default order is alphabetic by {@link Link#resolvedUri()}.
         *
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup randomOrder() {
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    headers,
                    rateLimit,
                    copyAndAdd(
                            streamTransformers,
                            stream -> {
                                final List<Link> complement = new ArrayList<>();
                                final List<Link> group = new ArrayList<>();
                                stream.forEach(link -> {
                                    if (pattern.matcher(link.resolvedUri()).matches()) {
                                        synchronized (group) {
                                            group.add(link);
                                        }
                                    } else {
                                        synchronized (complement) {
                                            complement.add(link);
                                        }
                                    }
                                });
                                Collections.shuffle(group);
                                return Stream.concat(complement.stream(), group.stream());
                            }),
                    continuationPolicies,
                    finalPolicies,
                    stats,
                    fragmentValidator);
        }

        /**
         * Apply the given {@code policy} before validating each {@link Links} of this {@link LinkGroup}.
         * <p>
         * Handy e.g. for skipping the rest of links in the {@link LinkGroup} upon encountering {@code 429 Too many requests}.
         *
         * @param policy the {@link AggregatePolicy} to apply
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup continuationPolicy(AggregatePolicy assertion) {
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    headers,
                    rateLimit,
                    streamTransformers,
                    copyAndAdd(continuationPolicies, assertion),
                    finalPolicies,
                    stats,
                    fragmentValidator);
        }

        /**
         * Apply the given {@code policy} after validating all {@link Links} of the parent {@link LinkStream}.
         * <p>
         * Handy for enforcing some positive assertions, such as at least n {@link Link}s valid for the given group,
         * when some {@link #continuationPolicy(AggregatePolicy)} is set that skips a portion of links, e.g. upon
         * encountering {@code 429 Too many requests}.
         *
         * @param policy the {@link AggregatePolicy} to apply
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup finalPolicy(AggregatePolicy policy) {
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    headers,
                    rateLimit,
                    streamTransformers,
                    continuationPolicies,
                    copyAndAdd(finalPolicies, policy),
                    stats,
                    fragmentValidator);
        }

        /**
         * Set Basic Authorization header.
         *
         * @param username
         * @param password
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup basicAuth(String username, String password) {
            return header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
        }

        /**
         * Set Bearer Authorization header using the given {@code token}.
         *
         * @param token
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup bearerToken(String token) {
            return header("Authorization", "Bearer " + token);
        }

        /**
         * Add the given HTTP header to all requests targeting this {@link LinkGroup}.
         *
         * @param key the HTTP header name
         * @param value the HTTP header value
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup header(String key, String value) {
            final Map<String, List<String>> newHeaders = new LinkedHashMap<>(headers);
            newHeaders.compute(key, (k, v) -> {
                if (v == null) {
                    return Collections.singletonList(value);
                } else {
                    v = new ArrayList<>(v);
                    v.add(value);
                    return Collections.unmodifiableList(v);
                }
            });
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    newHeaders,
                    rateLimit,
                    streamTransformers,
                    continuationPolicies,
                    finalPolicies,
                    stats,
                    fragmentValidator);
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        /**
         * Switch the original link to something else.
         * <p>
         * This is useful e.g. in case of some {@code http://github.com} links that are rate limited,
         * but once they are mapped to {@code http://api.github.com} and accessed with a {@link #bearerToken(String)}
         * then the limits are much higher and the result is equivalent.
         *
         * @param token
         * @return a new {@link LinkGroup}
         *
         * @since 2.0.0
         */
        public LinkGroup linkMapper(Function<Link, Link> linkMapper) {
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    headers,
                    rateLimit,
                    streamTransformers,
                    continuationPolicies,
                    finalPolicies,
                    stats,
                    fragmentValidator);
        }

        public LinkGroup fragmentValidator(FragmentValidator fragmentValidator) {
            return new LinkGroup(
                    parent,
                    pattern,
                    linkMapper,
                    headers,
                    rateLimit,
                    streamTransformers,
                    continuationPolicies,
                    finalPolicies,
                    stats,
                    fragmentValidator);
        }

        public FragmentValidator fragmentValidator() {
            return fragmentValidator;
        }

        /**
         * Add this {@link LinkGroup} to the parent {@link LinkStream}.
         *
         * @return the parent {@link LinkStream}
         *
         * @since 2.0.0
         */
        public LinkStream endGroup() {
            if (parent == null) {
                throw new IllegalStateException("Cannot end parentless group");
            }
            final List<LinkGroup> newGroups = new ArrayList<>(parent.groups);
            newGroups.add(parent.groups.size() - 1, this);
            return new LinkStream(parent.links, parent.resourceResolver, parent.retryAttempts, newGroups,
                    parent.overallTimeout);
        }

        /**
         * @return the {@link Pattern} defining this {@link LinkGroup}
         */
        public Pattern pattern() {
            return pattern;
        }

        /**
         * @return the {@link RateLimit} set on this {@link LinkGroup} or {@code null} if none has been set
         */
        public RateLimit rateLimit() {
            return rateLimit;
        }

        /**
         * @return the {@link LinkGroupStats} associated with this {@link LinkGroup}
         */
        public LinkGroupStats stats() {
            return stats;
        }

        ValidationResult applyFinalPolicies() {
            for (AggregatePolicy policy : finalPolicies) {
                AggregatePolicyResult result = policy.apply(stats);
                if (!result.isValid()) {
                    return ValidationResult.invalid(Link.ofResolved(pattern.pattern()), -5, result.message());
                }
            }
            return ValidationResult.valid(Link.ofResolved(pattern.pattern()), 0);
        }

        static <T> List<T> copyAndAdd(List<T> old, T newElement) {
            final ArrayList<T> result = new ArrayList<>(old);
            result.add(newElement);
            return result;
        }

    }
}
