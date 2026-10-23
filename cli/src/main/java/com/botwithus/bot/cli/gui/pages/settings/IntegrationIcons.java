package com.botwithus.bot.cli.gui.pages.settings;

/**
 * Font Awesome solid glyphs only the Integrations section uses; the shared ones
 * ({@code Icons.EYE}) come from {@link com.botwithus.bot.cli.gui.Icons}. The app bundles
 * the solid set alone, so the Slack and Discord brand marks are stood in for by a
 * hashtag (a Slack channel) and speech bubbles (a Discord channel); ntfy has no
 * mark in the design either and is drawn as its name.
 */
final class IntegrationIcons {

    static final String SHARE_NODES = "\uF1E0";  // fa-share-nodes
    static final String EYE_SLASH = "\uF070";    // fa-eye-slash
    static final String PAPER_PLANE = "\uF1D8";  // fa-paper-plane
    static final String HASHTAG = "\uF292";      // fa-hashtag
    static final String COMMENTS = "\uF086";     // fa-comments

    private IntegrationIcons() {
    }
}
