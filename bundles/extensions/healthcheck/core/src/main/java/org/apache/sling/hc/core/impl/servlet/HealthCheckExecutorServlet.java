/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.sling.hc.core.impl.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.hc.api.Result;
import org.apache.sling.hc.api.Result.Status;
import org.apache.sling.hc.api.execution.HealthCheckExecutionOptions;
import org.apache.sling.hc.api.execution.HealthCheckExecutionResult;
import org.apache.sling.hc.api.execution.HealthCheckExecutor;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Servlet that triggers the health check executor to return results via http. <br/>
 * <br/>
 * Parameters:<br/>
 * <br/>
 * tags: The health check tags to take into account<br/>
 * format: html|json|jsonp<br/>
 * includeDebug: If true, debug messages from result log are included.<br/>
 * callback: For jsonp, the JS callback function name (defaults to "processHealthCheckResults")<br/>
 * httpStatus: health check status to http status mapping in format httpStatus=WARN:418,CRITICAL:503,HEALTH_CHECK_ERROR:500. 
 * For omitted health check status values the next best code will be used (e.g. for httpStatus=CRITICAL:503 a result WARN will 
 * return 200, CRITICAL 503 and HEALTH_CHECK_ERROR also 503). By default all requests answer with an http status of 200. 
 * Useful in combination with load balancers.<br/>
 * 
 * NOTE: This servlet registers directly (low-level) at the HttpService and is not processed by sling (better performance, fewer dependencies, no authentication required, 503 can be sent without the progress tracker information). */
@Service
@Component(label = "Health Check Executor Servlet",
        description = "Serializes health check results into html or json format",
        policy = ConfigurationPolicy.REQUIRE, metatype = true, immediate = true)
public class HealthCheckExecutorServlet extends HttpServlet {
    private static final long serialVersionUID = 8013511523994541848L;

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckExecutorServlet.class);

    static final String PARAM_TAGS = "tags";
    static final String PARAM_FORMAT = "format";
    static final String PARAM_HTTP_STATUS = "httpStatus";

    static final String PARAM_COMBINE_TAGS_WITH_OR = "combineTagsWithOr";
    static final String PARAM_FORCE_INSTANT_EXECUTION = "forceInstantExecution";
    static final String PARAM_OVERRIDE_GLOBAL_TIMEOUT = "timeout";

    static final String PARAM_INCLUDE_DEBUG = "includeDebug";

    static final String FORMAT_HTML = "html";
    static final String FORMAT_JSON = "json";
    static final String FORMAT_JSONP = "jsonp";

    static final String PARAM_JSONP_CALLBACK = "callback";
    static final String JSONP_CALLBACK_DEFAULT = "processHealthCheckResults";

    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_JSONP = "application/javascript";
    private static final String STATUS_HEADER_NAME = "X-Health";

    private static final String CACHE_CONTROL_KEY = "Cache-control";
    private static final String CACHE_CONTROL_VALUE = "no-cache";

    private static final String SERVLET_PATH_DEFAULT = "/system/health";
    public static final String PROPERTY_SERVLET_PATH = "servletPath";
    @Property(name = PROPERTY_SERVLET_PATH, label = "Path",
            description = "Servlet path (defaults to " + SERVLET_PATH_DEFAULT + " in order to not be accessible via Apache/Internet)", value = SERVLET_PATH_DEFAULT)
    private String servletPath;

    @Reference
    private HttpService httpService;

    @Reference
    HealthCheckExecutor healthCheckExecutor;

    @Reference
    ResultHtmlSerializer htmlSerializer;

    @Reference
    ResultJsonSerializer jsonSerializer;

    @Activate
    protected final void activate(final ComponentContext context) {
        try {
            final Dictionary<?, ?> properties = context.getProperties();
            this.servletPath = (String) properties.get(PROPERTY_SERVLET_PATH);
            LOG.debug("Registering {} to path {}", getClass().getSimpleName(), this.servletPath);
            this.httpService.registerServlet(this.servletPath, this, null, null);
        } catch (Exception e) {
            LOG.error("Could not register health check servlet: "+e, e);
        }
    }

    @Deactivate
    public void deactivate(final ComponentContext componentContext) {
        try {
            LOG.debug("Unregistering path {}", this.servletPath);
            this.httpService.unregister(this.servletPath);
        } catch (Exception e) {
            LOG.error("Could not unregister health check servlet: "+e, e);
        }
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {

        final String[] tags = StringUtils.defaultIfEmpty(request.getParameter(PARAM_TAGS), "").split("[, ;]+");

        String format = StringUtils.substringAfterLast(request.getPathInfo(), ".");
        if (StringUtils.isBlank(format)) {
            // if not provided via extension use parameter or default
            format = StringUtils.defaultIfEmpty(request.getParameter(PARAM_FORMAT), FORMAT_HTML);
        }

        final Boolean includeDebug = Boolean.valueOf(request.getParameter(PARAM_INCLUDE_DEBUG));
        final Map<Result.Status, Integer> statusMapping = request.getParameter(PARAM_HTTP_STATUS) != null ? getStatusMapping(request
                .getParameter(PARAM_HTTP_STATUS)) : null;

        HealthCheckExecutionOptions options = new HealthCheckExecutionOptions();
        options.setCombineTagsWithOr(Boolean.valueOf(StringUtils.defaultString(request.getParameter(PARAM_COMBINE_TAGS_WITH_OR), "true")));
        options.setForceInstantExecution(Boolean.valueOf(request.getParameter(PARAM_FORCE_INSTANT_EXECUTION)));
        String overrideGlobalTimeoutVal = request.getParameter(PARAM_OVERRIDE_GLOBAL_TIMEOUT);
        if (StringUtils.isNumeric(overrideGlobalTimeoutVal)) {
            options.setOverrideGlobalTimeout(Integer.valueOf(overrideGlobalTimeoutVal));
        }

        List<HealthCheckExecutionResult> executionResults = this.healthCheckExecutor.execute(options, tags);

        Result.Status mostSevereStatus = Result.Status.DEBUG;
        for (HealthCheckExecutionResult executionResult : executionResults) {
            Status status = executionResult.getHealthCheckResult().getStatus();
            if (status.ordinal() > mostSevereStatus.ordinal()) {
                mostSevereStatus = status;
            }
        }
        Result overallResult = new Result(mostSevereStatus, "Overall status " + mostSevereStatus);

        sendNoCacheHeaders(response);

        if (statusMapping != null) {
            Integer httpStatus = statusMapping.get(overallResult.getStatus());
            response.setStatus(httpStatus);
        }

        if (FORMAT_HTML.equals(format)) {
            sendHtmlResponse(overallResult, executionResults, request, response, includeDebug);
        } else if (FORMAT_JSON.equals(format)) {
            sendJsonResponse(overallResult, executionResults, null, response, includeDebug);
        } else if (FORMAT_JSONP.equals(format)) {
            String jsonpCallback = StringUtils.defaultIfEmpty(request.getParameter(PARAM_JSONP_CALLBACK), JSONP_CALLBACK_DEFAULT);
            sendJsonResponse(overallResult, executionResults, jsonpCallback, response, includeDebug);
        } else {
            response.setContentType("text/plain");
            response.getWriter().println("Invalid format " + format + " - supported formats: html|json|jsonp");
        }

    }

    private void sendJsonResponse(final Result overallResult, final List<HealthCheckExecutionResult> executionResults, final String jsonpCallback,
            final HttpServletResponse response, boolean includeDebug)
            throws IOException {
        if (StringUtils.isNotBlank(jsonpCallback)) {
            response.setContentType(CONTENT_TYPE_JSONP);
        } else {
            response.setContentType(CONTENT_TYPE_JSON);
        }

        String resultJson = this.jsonSerializer.serialize(overallResult, executionResults, jsonpCallback, includeDebug);
        PrintWriter writer = response.getWriter();
        writer.append(resultJson);
    }

    private void sendHtmlResponse(final Result overallResult, final List<HealthCheckExecutionResult> executionResults,
            final HttpServletRequest request, final HttpServletResponse response, boolean includeDebug)
            throws IOException {

        String resultHtml = this.htmlSerializer.serialize(overallResult, executionResults, includeDebug);

        response.setContentType(CONTENT_TYPE_HTML);
        response.setHeader(STATUS_HEADER_NAME, overallResult.toString());

        PrintWriter writer = response.getWriter();
        writer.append(resultHtml);

    }

    private void sendNoCacheHeaders(final HttpServletResponse response) {
        response.setHeader(CACHE_CONTROL_KEY, CACHE_CONTROL_VALUE);
    }

    Map<Result.Status, Integer> getStatusMapping(String mappingStr) throws ServletException {
        Map<Result.Status, Integer> statusMapping = new HashMap<Result.Status, Integer>();
        try {
            String[] bits = mappingStr.split("[,]");
            for (String bit : bits) {
                String[] tuple = bit.split("[:]");
                statusMapping.put(Result.Status.valueOf(tuple[0]), Integer.parseInt(tuple[1]));
            }
        } catch (Exception e) {
            throw new ServletException("Invalid parameter httpStatus=" + mappingStr + " " + e, e);
        }

        if (!statusMapping.containsKey(Result.Status.OK)) {
            statusMapping.put(Result.Status.OK, 200);
        }
        if (!statusMapping.containsKey(Result.Status.WARN)) {
            statusMapping.put(Result.Status.WARN, statusMapping.get(Result.Status.OK));
        }
        if (!statusMapping.containsKey(Result.Status.CRITICAL)) {
            statusMapping.put(Result.Status.CRITICAL, statusMapping.get(Result.Status.WARN));
        }
        if (!statusMapping.containsKey(Result.Status.HEALTH_CHECK_ERROR)) {
            statusMapping.put(Result.Status.HEALTH_CHECK_ERROR, statusMapping.get(Result.Status.CRITICAL));
        }
        return statusMapping;
    }


}
