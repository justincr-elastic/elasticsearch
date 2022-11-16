/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.transport;

import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptorsIntersection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public record RemoteAccessAuthentication(Authentication authentication, Collection<BytesReference> roleDescriptorsBytesIntersection) {

    private static final String REMOTE_ACCESS_AUTHENTICATION_HEADER = "_remote_access_authentication";

    public static void writeToContext(
        final ThreadContext ctx,
        final Authentication authentication,
        final RoleDescriptorsIntersection roleDescriptorsIntersection
    ) throws IOException {
        ctx.putHeader(REMOTE_ACCESS_AUTHENTICATION_HEADER, encode(authentication, roleDescriptorsIntersection));
    }

    static String encode(final Authentication authentication, final RoleDescriptorsIntersection roleDescriptorsIntersection)
        throws IOException {
        final BytesStreamOutput out = new BytesStreamOutput();
        out.setVersion(authentication.getEffectiveSubject().getVersion());
        Version.writeVersion(authentication.getEffectiveSubject().getVersion(), out);
        authentication.writeTo(out);
        out.writeVInt(roleDescriptorsIntersection.roleDescriptorsList().size());
        for (var roleDescriptors : roleDescriptorsIntersection.roleDescriptorsList()) {
            final XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            for (RoleDescriptor roleDescriptor : roleDescriptors) {
                builder.field(roleDescriptor.getName(), roleDescriptor);
            }
            builder.endObject();
            out.writeBytesReference(BytesReference.bytes(builder));
        }
        return Base64.getEncoder().encodeToString(BytesReference.toBytes(out.bytes()));
    }

    public static RemoteAccessAuthentication readFromContext(final ThreadContext ctx) throws IOException {
        return decode(ctx.getHeader(REMOTE_ACCESS_AUTHENTICATION_HEADER));
    }

    static RemoteAccessAuthentication decode(final String header) throws IOException {
        final byte[] bytes = Base64.getDecoder().decode(header);
        final StreamInput in = StreamInput.wrap(bytes);
        final Version version = Version.readVersion(in);
        in.setVersion(version);
        final Authentication authentication = new Authentication(in);
        final Collection<BytesReference> roleDescriptorsBytesIntersection = new ArrayList<>();
        final int outerCount = in.readVInt();
        for (int i = 0; i < outerCount; i++) {
            roleDescriptorsBytesIntersection.add(in.readBytesReference());
        }
        return new RemoteAccessAuthentication(authentication, roleDescriptorsBytesIntersection);
    }

    static Set<RoleDescriptor> parseRoleDescriptorBytes(final BytesReference roleDescriptorBytes) {
        try (
            XContentParser parser = XContentHelper.createParser(XContentParserConfiguration.EMPTY, roleDescriptorBytes, XContentType.JSON)
        ) {
            final List<RoleDescriptor> roleDescriptors = new ArrayList<>();
            parser.nextToken();
            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                parser.nextToken();
                final String roleName = parser.currentName();
                roleDescriptors.add(RoleDescriptor.parse(roleName, parser, false));
            }
            return Set.copyOf(roleDescriptors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
