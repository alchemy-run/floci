package io.floci.aws.services.ivs;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes a channel's playback URL, {@code /api/video/v1/<region>.<account>.channel.<id>.m3u8}, to
 * {@link IvsPlaybackController}, its query (a playback token) kept.
 */
@Provider
@PreMatching
@Priority(5)
public class IvsPlaybackRoutingFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path == null || !path.startsWith(IvsPlaybackController.PLAYBACK_PREFIX) || !path.endsWith(".m3u8")
                || !"GET".equals(context.getMethod())) {
            return;
        }
        String playback = path.substring(IvsPlaybackController.PLAYBACK_PREFIX.length());
        if (playback.contains("/") || !playback.contains(".channel.")) {
            return;
        }
        context.setRequestUri(context.getUriInfo().getRequestUriBuilder()
                .replacePath(IvsPlaybackController.HLS_PREFIX + "play/" + playback).buildFromEncoded());
    }
}
