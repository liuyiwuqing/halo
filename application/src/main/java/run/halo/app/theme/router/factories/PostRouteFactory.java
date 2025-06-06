package run.halo.app.theme.router.factories;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static run.halo.app.content.permalinks.PostPermalinkPolicy.DEFAULT_CATEGORY;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.i18n.LocaleContextResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.QueryFactory;
import run.halo.app.infra.exception.NotFoundException;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.theme.DefaultTemplateEnum;
import run.halo.app.theme.ViewNameResolver;
import run.halo.app.theme.finders.PostFinder;
import run.halo.app.theme.finders.vo.PostVo;
import run.halo.app.theme.router.ModelMapUtils;
import run.halo.app.theme.router.ReactiveQueryPostPredicateResolver;
import run.halo.app.theme.router.TitleVisibilityIdentifyCalculator;

/**
 * The {@link PostRouteFactory} for generate {@link RouterFunction} specific to the template
 * <code>post.html</code>.
 *
 * @author guqing
 * @since 2.0.0
 */
@Component
@AllArgsConstructor
public class PostRouteFactory implements RouteFactory {

    private final PostFinder postFinder;

    private final ViewNameResolver viewNameResolver;

    private final ReactiveExtensionClient client;

    private final ReactiveQueryPostPredicateResolver queryPostPredicateResolver;

    private final TitleVisibilityIdentifyCalculator titleVisibilityIdentifyCalculator;

    private final LocaleContextResolver localeContextResolver;
    private final PostService postService;

    @Override
    public RouterFunction<ServerResponse> create(String pattern) {
        PatternParser postParamPredicate =
            new PatternParser(pattern);
        if (postParamPredicate.isQueryParamPattern()) {
            RequestPredicate requestPredicate = postParamPredicate.toRequestPredicate();
            return RouterFunctions.route(GET("/")
                .and(requestPredicate), queryParamHandlerFunction(postParamPredicate));
        }
        return RouterFunctions
            .route(GET(pattern).and(accept(MediaType.TEXT_HTML)), handlerFunction());
    }

    HandlerFunction<ServerResponse> queryParamHandlerFunction(PatternParser paramPredicate) {
        return request -> {
            Map<String, String> variables = mergedVariables(request);
            PostPatternVariable patternVariable = new PostPatternVariable();
            Optional.ofNullable(variables.get(paramPredicate.getParamName()))
                .ifPresent(value -> {
                    switch (paramPredicate.getPlaceholderName()) {
                        case "name" -> patternVariable.setName(value);
                        case "slug" -> patternVariable.setSlug(value);
                        default ->
                            throw new IllegalArgumentException("Unsupported query param predicate");
                    }
                });
            return postResponse(request, patternVariable);
        };
    }

    HandlerFunction<ServerResponse> handlerFunction() {
        return request -> {
            PostPatternVariable patternVariable = PostPatternVariable.from(request);
            return postResponse(request, patternVariable)
                .switchIfEmpty(Mono.error(() -> new NotFoundException("Post not found.")));
        };
    }

    @NonNull
    private Mono<ServerResponse> postResponse(ServerRequest request,
        PostPatternVariable patternVariable) {
        Mono<PostVo> postVoMono = bestMatchPost(patternVariable);
        return postVoMono
            .doOnNext(postVo -> {
                postVo.getSpec().setTitle(
                    titleVisibilityIdentifyCalculator.calculateTitle(
                        postVo.getSpec().getTitle(),
                        postVo.getSpec().getVisible(),
                        localeContextResolver.resolveLocaleContext(request.exchange())
                            .getLocale())
                );
            })
            .flatMap(postVo -> {
                Map<String, Object> model = ModelMapUtils.postModel(postVo);
                return determineTemplate(request, postVo)
                    .flatMap(templateName -> ServerResponse.ok().render(templateName, model));
            });
    }

    Mono<String> determineTemplate(ServerRequest request, PostVo postVo) {
        return Flux.fromIterable(defaultIfNull(postVo.getCategories(), List.of()))
            .filter(category -> isNotBlank(category.getSpec().getPostTemplate()))
            .concatMap(category -> viewNameResolver.resolveViewNameOrDefault(request,
                category.getSpec().getPostTemplate(), null)
            )
            .next()
            .switchIfEmpty(Mono.defer(() -> viewNameResolver.resolveViewNameOrDefault(request,
                postVo.getSpec().getTemplate(),
                DefaultTemplateEnum.POST.getValue())
            ));
    }

    Mono<PostVo> bestMatchPost(PostPatternVariable variable) {
        return postsByPredicates(variable)
            .filter(post -> {
                Map<String, String> labels = MetadataUtil.nullSafeLabels(post);
                return matchIfPresent(variable.getName(), post.getMetadata().getName())
                    && matchIfPresent(variable.getSlug(), post.getSpec().getSlug())
                    && matchIfPresent(variable.getYear(), labels.get(Post.ARCHIVE_YEAR_LABEL))
                    && matchIfPresent(variable.getMonth(), labels.get(Post.ARCHIVE_MONTH_LABEL))
                    && matchIfPresent(variable.getDay(), labels.get(Post.ARCHIVE_DAY_LABEL));
            })
            .filterWhen(post -> {
                if (isNotBlank(variable.getCategorySlug())) {
                    var categoryNames = post.getSpec().getCategories();
                    return postService.listCategories(categoryNames)
                        .next()
                        .filter(category -> category.getSpec().getSlug()
                            .equals(variable.getCategorySlug())
                        )
                        .map(category -> category.getSpec().getSlug())
                        .switchIfEmpty(Mono.defer(() -> {
                            if (DEFAULT_CATEGORY.equals(variable.getCategorySlug())) {
                                return Mono.just(DEFAULT_CATEGORY);
                            }
                            return Mono.empty();
                        }))
                        .hasElement();
                }
                return Mono.just(true);
            })
            .next()
            .flatMap(post -> postFinder.getByName(post.getMetadata().getName()));
    }

    Flux<Post> postsByPredicates(PostPatternVariable patternVariable) {
        if (isNotBlank(patternVariable.getName())) {
            return fetchPostsByName(patternVariable.getName());
        }
        if (isNotBlank(patternVariable.getSlug())) {
            return fetchPostsBySlug(patternVariable.getSlug());
        }
        return Flux.empty();
    }

    private Flux<Post> fetchPostsByName(String name) {
        return queryPostPredicateResolver.getPredicate()
            .flatMap(predicate -> client.fetch(Post.class, name)
                .filter(predicate)
            )
            .flux();
    }

    private Flux<Post> fetchPostsBySlug(String slug) {
        return queryPostPredicateResolver.getListOptions()
            .flatMapMany(listOptions -> {
                if (isNotBlank(slug)) {
                    var other = QueryFactory.equal("spec.slug", slug);
                    listOptions.setFieldSelector(listOptions.getFieldSelector().andQuery(other));
                }
                return client.listAll(Post.class, listOptions, Sort.unsorted());
            });
    }

    private boolean matchIfPresent(String variable, String target) {
        return StringUtils.isBlank(variable) || StringUtils.equals(target, variable);
    }

    @Data
    static class PostPatternVariable {
        String name;
        String slug;
        String year;
        String month;
        String day;
        String categorySlug;

        static PostPatternVariable from(ServerRequest request) {
            Map<String, String> variables = mergedVariables(request);
            return JsonUtils.mapToObject(variables, PostPatternVariable.class);
        }
    }

    static Map<String, String> mergedVariables(ServerRequest request) {
        Map<String, String> pathVariables = request.pathVariables();
        MultiValueMap<String, String> queryParams = request.queryParams();
        Map<String, String> mergedVariables = new LinkedHashMap<>();
        for (String paramKey : queryParams.keySet()) {
            mergedVariables.put(paramKey, queryParams.getFirst(paramKey));
        }
        // path variables higher priority will override query params
        mergedVariables.putAll(pathVariables);
        return mergedVariables;
    }

    @Getter
    static class PatternParser {
        private static final Pattern PATTERN_COMPILE = Pattern.compile("([^&?]*)=\\{(.*?)\\}(&|$)");

        private final String pattern;
        private String paramName;
        private String placeholderName;
        private final boolean isQueryParamPattern;

        PatternParser(String pattern) {
            this.pattern = pattern;
            var matcher = PATTERN_COMPILE.matcher(pattern);
            if (matcher.find()) {
                this.paramName = matcher.group(1);
                this.placeholderName = matcher.group(2);
                this.isQueryParamPattern = true;
            } else {
                this.isQueryParamPattern = false;
            }
        }

        RequestPredicate toRequestPredicate() {
            if (!this.isQueryParamPattern) {
                throw new IllegalStateException("Not a query param pattern: " + pattern);
            }

            return RequestPredicates.queryParam(paramName, value -> true);
        }
    }
}
