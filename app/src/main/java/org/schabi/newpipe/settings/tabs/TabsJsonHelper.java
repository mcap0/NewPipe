package org.schabi.newpipe.settings.tabs;

import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonStringWriter;
import com.grack.nanojson.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Class to get a JSON representation of a list of tabs, and the other way around.
 */
public final class TabsJsonHelper {
    private static final String JSON_TABS_ARRAY_KEY = "tabs";

    private static final List<Tab> FALLBACK_INITIAL_TABS_LIST = List.of(
            Tab.Type.PERLE.getTab(),
            Tab.Type.FEED.getTab(),
            Tab.Type.SUBSCRIPTIONS.getTab(),
            Tab.Type.BOOKMARKS.getTab());

    private TabsJsonHelper() { }

    /**
     * Try to reads the passed JSON and returns the list of tabs if no error were encountered.
     * <p>
     * If the JSON is null or empty, or the list of tabs that it represents is empty, the
     * {@link #getDefaultTabs fallback list} will be returned.
     * <p>
     * Tabs with invalid ids (i.e. not in the {@link Tab.Type} enum) will be ignored.
     *
     * @param tabsJson a JSON string got from {@link #getJsonToSave(List)}.
     * @return a list of {@link Tab tabs}.
     * @throws InvalidJsonException if the JSON string is not valid
     */
    public static List<Tab> getTabsFromJson(@Nullable final String tabsJson)
            throws InvalidJsonException {
        if (tabsJson == null || tabsJson.isEmpty()) {
            return getDefaultTabs();
        }

        try {
            final JsonObject outerJsonObject = JsonParser.object().from(tabsJson);

            if (!outerJsonObject.has(JSON_TABS_ARRAY_KEY)) {
                throw new InvalidJsonException("JSON doesn't contain \"" + JSON_TABS_ARRAY_KEY
                        + "\" array");
            }

            final JsonArray tabsArray = outerJsonObject.getArray(JSON_TABS_ARRAY_KEY, null);

            final var returnTabs = tabsArray.streamAsJsonObjects()
                    .map(Tab::from)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableList());

            return returnTabs.isEmpty() ? getDefaultTabs() : returnTabs;
        } catch (final JsonParserException e) {
            throw new InvalidJsonException(e);
        }
    }

    /**
     * Get a JSON representation from a list of tabs.
     *
     * @param tabList a list of {@link Tab tabs}.
     * @return a JSON string representing the list of tabs
     */
    public static String getJsonToSave(@Nullable final List<Tab> tabList) {
        final JsonStringWriter jsonWriter = JsonWriter.string();
        jsonWriter.object();

        jsonWriter.array(JSON_TABS_ARRAY_KEY);
        if (tabList != null) {
            for (final Tab tab : tabList) {
                tab.writeJsonOn(jsonWriter);
            }
        }
        jsonWriter.end();

        jsonWriter.end();
        return jsonWriter.done();
    }

    public static List<Tab> getDefaultTabs() {
        return FALLBACK_INITIAL_TABS_LIST;
    }

    /**
     * Puts the Perle tab where the kiosk (e.g. "Live") was: in place of the default kiosk tab, or
     * else of the first kiosk tab, or else as the first tab. Lists already containing Perle are
     * returned unchanged.
     *
     * @param tabs the tabs saved by the user
     * @return a new list with the Perle tab
     */
    public static List<Tab> withPerleTab(final List<Tab> tabs) {
        final Tab perle = Tab.Type.PERLE.getTab();
        final List<Tab> result = new ArrayList<>(tabs);
        if (result.contains(perle)) {
            return result;
        }

        int index = indexOfType(result, Tab.Type.DEFAULT_KIOSK);
        if (index < 0) {
            index = indexOfType(result, Tab.Type.KIOSK);
        }
        if (index >= 0) {
            result.set(index, perle);
        } else {
            result.add(0, perle);
        }
        return result;
    }

    private static int indexOfType(final List<Tab> tabs, final Tab.Type type) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getTabId() == type.getTabId()) {
                return i;
            }
        }
        return -1;
    }

    public static final class InvalidJsonException extends Exception {
        private InvalidJsonException() {
            super();
        }

        private InvalidJsonException(final String message) {
            super(message);
        }

        private InvalidJsonException(final Throwable cause) {
            super(cause);
        }
    }
}
