package com.botwithus.bot.api.dialog;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.util.Interfaces;

import java.util.Optional;

/**
 * The four one-page dialogs a click-to-continue advances, and where each keeps its text,
 * its speaker's name and its continue button. Every component is addressed by gameval name,
 * with the id it had when this was written as the fallback.
 */
enum ChatPage {

    /** An NPC speaking, chathead on the left ({@code CHAT_V2_LEFT}, 1184). */
    NPC("CHAT_V2_LEFT", 1184, "CHAT_TEXT", 10, "TITLE_TEXT", 4, "CLICK_CONTINUE", 15),
    /** The player speaking, chathead on the right ({@code CHAT_V2_RIGHT}, 1191). */
    PLAYER("CHAT_V2_RIGHT", 1191, "CHAT_TEXT", 10, "TITLE_TEXT", 4, "CLICK_CONTINUE", 15),
    /** A plain message box with no speaker ({@code MESBOX_V2}, 1186). */
    MESSAGE("MESBOX_V2", 1186, "MESBOX_TEXT", 3, null, 0, "CLICK_CONTINUE", 8),
    /** A message box showing an item ({@code OBJBOX_V2}, 1189). */
    OBJECT("OBJBOX_V2", 1189, "OBJBOX_TEXT", 3, null, 0, "CLICK_CONTINUE", 20);

    /** Separates an interface's gameval from its component's in a component gameval. */
    private static final String COMPONENT_SEPARATOR = "__";

    private final GamevalRef iface;
    private final GamevalRef text;
    private final GamevalRef title;
    private final GamevalRef continueButton;

    ChatPage(String ifaceName, int ifaceId, String textName, int textComp, String titleName,
             int titleComp, String continueName, int continueComp) {
        this.iface = new GamevalRef(GamevalType.INTERFACE, ifaceName, ifaceId);
        this.text = component(ifaceName, ifaceId, textName, textComp);
        this.title = titleName == null ? null : component(ifaceName, ifaceId, titleName, titleComp);
        this.continueButton = component(ifaceName, ifaceId, continueName, continueComp);
    }

    /** The first of these dialogs open now, in declaration order, or empty when none is. */
    static Optional<ChatPage> open(GameAPI api) {
        for (ChatPage page : values()) {
            if (page.isOpen(api)) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }

    boolean isOpen(GameAPI api) {
        return api.isInterfaceOpen(iface.resolve(api.gamevals()));
    }

    /** The page's body text as the client holds it, markup included. One round-trip. */
    Optional<String> text(GameAPI api) {
        return read(api, text);
    }

    /** The speaker's name; empty for a page with no speaker. One round-trip. */
    Optional<String> title(GameAPI api) {
        return title == null ? Optional.empty() : read(api, title);
    }

    /** Packed {@code (iface << 16) | comp} of the button that advances the page. */
    int continueHash(GameAPI api) {
        return continueButton.resolve(api.gamevals());
    }

    private static Optional<String> read(GameAPI api, GamevalRef ref) {
        int hash = ref.resolve(api.gamevals());
        Component component = api.getComponent(Interfaces.interfaceIdFromHash(hash),
                Interfaces.componentIdFromHash(hash));
        return component == null ? Optional.empty() : Optional.ofNullable(component.text());
    }

    private static GamevalRef component(String ifaceName, int ifaceId, String name, int comp) {
        return new GamevalRef(GamevalType.COMPONENT, ifaceName + COMPONENT_SEPARATOR + name,
                Interfaces.componentHash(ifaceId, comp));
    }
}
