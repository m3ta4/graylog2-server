/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.audit.jersey;

import org.glassfish.jersey.server.model.ModelProcessor;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.model.ResourceModel;
import org.graylog2.audit.PluginAuditActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.core.Configuration;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks all POST, PUT and DELETE resource methods for {@link AuditEvent} annotations and reports missing ones.
 *
 * It does not report methods which have a {@link NoAuditEvent} annotation.
 */
public class AuditEventModelProcessor implements ModelProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(AuditEventModelProcessor.class);
    private final Set<String> auditActions;

    public AuditEventModelProcessor(final Set<PluginAuditActions> auditActions) {
        this.auditActions = auditActions.stream()
                .flatMap(actions -> actions.auditActions().stream())
                .collect(Collectors.toSet());
    }

    @Override
    public ResourceModel processResourceModel(ResourceModel resourceModel, Configuration configuration) {
        checkResources(resourceModel.getResources());

        return resourceModel;
    }

    @Override
    public ResourceModel processSubResource(ResourceModel subResourceModel, Configuration configuration) {
        checkResources(subResourceModel.getResources());

        return subResourceModel;
    }

    private void checkResources(List<Resource> resources) {

        for (Resource resource : resources) {
            for (ResourceMethod method : resource.getResourceMethods()) {
                final Method m = method.getInvocable().getDefinitionMethod();

                if (m.isAnnotationPresent(POST.class) || m.isAnnotationPresent(PUT.class) || m.isAnnotationPresent(DELETE.class)) {
                    if (!m.isAnnotationPresent(AuditEvent.class) && !m.isAnnotationPresent(NoAuditEvent.class)) {
                        LOG.warn("REST endpoint not included in audit trail: {}", String.format(Locale.US, "%6s %s", method.getHttpMethod(), getPath(resource)));
                        LOG.debug("Missing @AuditEvent or @NoAuditEvent annotation: {}#{}", m.getDeclaringClass().getCanonicalName(), m.getName());
                    } else {
                        if (m.isAnnotationPresent(AuditEvent.class)) {
                            final AuditEvent annotation = m.getAnnotation(AuditEvent.class);

                            if (!auditActions.contains(annotation.action())) {
                                LOG.warn("REST endpoint does not use a registered audit action: {} (action: \"{}\")",
                                        String.format(Locale.US, "%6s %s", method.getHttpMethod(), getPath(resource)), annotation.action());
                                LOG.debug("Make sure the audit actions are registered in a class that implements PluginAuditActions: {}#{}",
                                        m.getDeclaringClass().getCanonicalName(), m.getName());
                            }
                        }
                    }
                }
            }

            // Make sure to also check all child resources! Otherwise some resources will not be checked.
            checkResources(resource.getChildResources());
        }
    }

    private String getPath(Resource resource) {
        String path = resource.getPath();
        Resource parent = resource.getParent();

        while (parent != null) {
            if (!path.startsWith("/")) {
                path = "/" + path;
            }

            path = parent.getPath() + path;
            parent = parent.getParent();
        }

        return path;
    }
}
