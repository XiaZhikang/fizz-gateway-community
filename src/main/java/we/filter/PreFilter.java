/*
 *  Copyright (C) 2020 the original author or authors.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package we.filter;

import we.config.SystemConfig;
import we.plugin.FixedPluginFilter;
import we.plugin.PluginConfig;
import we.plugin.PluginFilter;
import we.plugin.auth.*;
import we.plugin.stat.StatPluginFilter;
import we.util.Constants;
import we.util.ReactorUtils;
import we.util.WebUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.function.Function;

/**
 * @author lancer
 */
@Component(PreFilter.PRE_FILTER)
@Order(1)
public class PreFilter extends ProxyAggrFilter {

    private static final Logger       log        = LoggerFactory.getLogger(PreFilter.class);

    public  static final String       PRE_FILTER = "preFilter";

    private static final FilterResult succFr     = FilterResult.SUCCESS(PRE_FILTER);

    @Value("${spring.profiles.active}")
    private String profile;

    @Value("${b-services:x}")
    private Set<String> bServices = new HashSet<>();

    @Resource
    private SystemConfig systemConfig;

    @Resource(name = StatPluginFilter.STAT_PLUGIN_FILTER)
    private StatPluginFilter statPluginFilter;

    @Resource(name = AuthPluginFilter.AUTH_PLUGIN_FILTER)
    private AuthPluginFilter authPluginFilter;

    private char currentGatewayGroup;
    @PostConstruct
    public void setCurrentGatewayGroup() {
        for (Character gg : systemConfig.getCurrentServerGatewayGroupSet()) {
            currentGatewayGroup = gg.charValue();
            log.info("current gateway group is " + currentGatewayGroup);
            break;
        }
    }

    @Override
    public Mono<Void> doFilter(ServerWebExchange exchange, WebFilterChain chain) {

        Map<String, FilterResult> fc         = new HashMap<>(6, 1.0f);          fc.put(WebUtils.PREV_FILTER_RESULT, succFr);
        Map<String, String>       appendHdrs = new HashMap<>(6, 1.0f);
        Map<String, Object>       eas        = exchange.getAttributes();       eas.put(WebUtils.FILTER_CONTEXT,     fc);
                                                                               eas.put(WebUtils.APPEND_HEADERS,     appendHdrs);
                                                                               eas.put(WebUtils.CGG,                currentGatewayGroup);

        String app = WebUtils.getHeaderValue(exchange, WebUtils.APP_HEADER);
        if (StringUtils.isBlank(app)) {
            if (Constants.Profiles.DEV.equals(profile) || Constants.Profiles.TEST.equals(profile)) {
                String service = WebUtils.getServiceId(exchange);
                if (bServices.contains(service)) {
                    app = App.TO_B;
                } else {
                    app = App.TO_C;
                }
            } else if (currentGatewayGroup == GatewayGroup.B) {
                app = App.TO_B;
            } else {
                app = App.TO_C;
            }
        }
        eas.put(WebUtils.APP_HEADER, app);

        Mono vm = statPluginFilter.filter(exchange, null, null);
        return chain(exchange, vm, authPluginFilter).defaultIfEmpty(ReactorUtils.NULL)
                .flatMap(
                        v -> {
                            Object authRes = WebUtils.getFilterResultDataItem(exchange, AuthPluginFilter.AUTH_PLUGIN_FILTER, AuthPluginFilter.RESULT);
                            Mono m;
                            if (authRes instanceof ApiConfig) {
                                ApiConfig ac = (ApiConfig) authRes;
                                m = executeFixedPluginFilters(exchange);
                                m = m.defaultIfEmpty(ReactorUtils.NULL);
                                if (ac.pluginConfigs == null || ac.pluginConfigs.isEmpty()) {
                                    return m.flatMap(func(exchange, chain));
                                } else {
                                    return m.flatMap(
                                                    e -> {
                                                        return executeManagedPluginFilters(exchange, ac.pluginConfigs);
                                                    }
                                            )
                                            .defaultIfEmpty(ReactorUtils.NULL).flatMap(func(exchange, chain));
                                }
                            } else if (authRes == ApiConfigService.Access.YES) {
                                m = executeFixedPluginFilters(exchange);
                                return m.defaultIfEmpty(ReactorUtils.NULL).flatMap(func(exchange, chain));
                            } else {
                                ApiConfigService.Access access = (ApiConfigService.Access) authRes;
                                return WebUtils.responseError(exchange, HttpStatus.FORBIDDEN.value(), access.getReason());
                            }
                        }
                );
    }

    private Mono chain(ServerWebExchange exchange, Mono m, PluginFilter pf) {
        return m.defaultIfEmpty(ReactorUtils.NULL).flatMap(
                v -> {
                    return pf.filter(exchange, null, null);
                }
        );
    }

    private Function func(ServerWebExchange exchange, WebFilterChain chain) {
        return v -> {
            Mono<Void> dr = WebUtils.getDirectResponse(exchange);
            if (dr != null) {
                return dr;
            }
            return chain.filter(exchange);
        };
    }

    private Mono<Void> executeFixedPluginFilters(ServerWebExchange exchange) {
        Mono vm = Mono.empty();
        List<FixedPluginFilter> fixedPluginFilters = FixedPluginFilter.getPluginFilters();
        for (byte i = 0; i < fixedPluginFilters.size(); i++) {
            FixedPluginFilter fpf = fixedPluginFilters.get(i);
            vm = vm.defaultIfEmpty(ReactorUtils.NULL).flatMap(
                    v -> {
                        return fpf.filter(exchange, null, null);
                    }
            );
        }
        return vm;
    }

    private Mono<Void> executeManagedPluginFilters(ServerWebExchange exchange, List<PluginConfig> pluginConfigs) {
        Mono vm = Mono.empty();
        ApplicationContext app = exchange.getApplicationContext();
        for (byte i = 0; i < pluginConfigs.size(); i++) {
            PluginConfig pc = pluginConfigs.get(i);
            PluginFilter pf = app.getBean(pc.plugin, PluginFilter.class);
            vm = vm.defaultIfEmpty(ReactorUtils.NULL).flatMap(
                    v -> {
                        return pf.filter(exchange, pc.config, pc.fixedConfig);
                    }
            );
        }
        return vm;
    }
}
