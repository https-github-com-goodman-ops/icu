// © 2019 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
package org.unicode.icu.tool.cldrtoicu;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.Character.DIRECTIONALITY_LEFT_TO_RIGHT;
import static java.util.function.Function.identity;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.unicode.cldr.api.CldrDataSupplier.CldrResolution.RESOLVED;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.unicode.cldr.api.CldrData;
import org.unicode.cldr.api.CldrDataSupplier;
import org.unicode.cldr.api.CldrDataSupplier.CldrResolution;
import org.unicode.cldr.api.CldrDataType;
import org.unicode.cldr.api.CldrDraftStatus;
import org.unicode.cldr.api.CldrPath;
import org.unicode.cldr.api.CldrValue;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * A factory for wrapping data suppliers to add synthetic locales for debugging. The currently
 * supported synthetic locales are:
 * <ul>
 *     <li>{@code en_XA}: A pseudo locale which generates expanded text with many non-Latin accents.
 *     <li>{@code ar_XB}: A pseudo locale which generates BiDi text for debugging.
 * </ul>
 *
 * <p>Both pseudo locales are based on {@code "en"} data, and generate values which are readable
 * by English speaking developers. For example, the CLDR value "Hello World" will be turned into
 * something like:
 * <ul>
 *     <li>{@code en_XA}: [Ĥéļļö Ŵöŕļð one two]
 *     <li>{@code ar_XB}: dlroW elloH
 * </ul>
 *
 * <p>In the case of BiDi pseudo localization, bi-directional markers are also inserted into the
 * text so that, if the system using the data is configured correctly, the results will look
 * "normal" (i.e. Latin text will appear displayed left-to-right because of the BiDi markers).
 */
// TODO(CLDR-13381): Move this all into the CLDR API once the dust has settled.
public final class PseudoLocales {
    private enum PseudoType {
        BIDI("ar_XB", PseudoLocales::bidi, "abcdefghijklmnopqrstuvwxyz"),
        EXPAND("en_XA", PseudoLocales::expanding,
            "a\u00e5b\u0180c\u00e7d\u00f0e\u00e9f\u0192g\u011dh\u0125i\u00eej\u0135k\u0137l\u013cm"
                + "\u0271n\u00f1o\u00f6p\u00feq\u01ebr\u0155s\u0161t\u0163u\u00fbv\u1e7dw\u0175"
                + "x\u1e8by\u00fdz\u017e");

        private static final ImmutableMap<String, PseudoType> ID_MAP =
            Arrays.stream(values()).collect(toImmutableMap(PseudoType::getLocaleId, identity()));

        private static PseudoType fromId(String localeId) {
            return checkNotNull(ID_MAP.get(localeId), "unknown pseduo locale: %s", localeId);
        }

        private static ImmutableSet<String> getLocaleIds() {
            return ID_MAP.keySet();
        }

        private final String localeId;
        private final Function<Boolean, PseudoText> textSupplier;
        // A string whose code points form the exemplar set for the pseudo locale.
        private final String exemplars;

        PseudoType(String localeId, Function<Boolean, PseudoText> textSupplier, String exemplars) {
            this.localeId = localeId;
            this.textSupplier = textSupplier;
            this.exemplars = exemplars;
        }

        String getLocaleId() {
            return localeId;
        }

        PseudoText getText(boolean isPattern) {
            return textSupplier.apply(isPattern);
        }

        String getExemplars() {
            return exemplars;
        }
    }

    /**
     * Returns a wrapped data supplier which will inject {@link CldrData} for the pseudo locales
     * {@code en_XA} and {@code ar_XB}. These locales should behave in all respects like normal
     * locales and can be processed accordingly.
     */
    public static CldrDataSupplier addPseudoLocalesTo(CldrDataSupplier src) {
        return new PseudoSupplier(src);
    }

    private static final class PseudoSupplier extends CldrDataSupplier {
        private final CldrDataSupplier src;
        private final Set<String> srcIds;
        private final CldrData enData;

        PseudoSupplier(CldrDataSupplier src) {
            this.src = checkNotNull(src);
            this.srcIds = src.getAvailableLocaleIds();
            // Use resolved data to ensure we get all the values (e.g. values in "en_001").
            this.enData = src.getDataForLocale("en", RESOLVED);
            // Just check that we aren't wrapping an already wrapped supplier.
            PseudoType.getLocaleIds()
                .forEach(id -> checkArgument(!srcIds.contains(id),
                    "pseudo locale %s already supported by given data supplier", id));
        }

        @Override public CldrDataSupplier withDraftStatusAtLeast(CldrDraftStatus draftStatus) {
            return new PseudoSupplier(src.withDraftStatusAtLeast(draftStatus));
        }

        @Override public CldrData getDataForLocale(String localeId, CldrResolution resolution) {
            if (PseudoType.getLocaleIds().contains(localeId)) {
                return new PseudoLocaleData(enData, resolution, PseudoType.fromId(localeId));
            } else {
                return src.getDataForLocale(localeId, resolution);
            }
        }

        @Override public Set<String> getAvailableLocaleIds() {
            return Sets.union(src.getAvailableLocaleIds(), PseudoType.getLocaleIds());
        }

        @Override public CldrData getDataForType(CldrDataType type) {
            return src.getDataForType(type);
        }
    }

    private interface PseudoText {
        void addFragment(String text, boolean isLocalizable);
    }

    private static final class PseudoLocaleData extends FilteredData {
        private static final PathMatcher AUX_EXEMPLARS =
            PathMatcher.of("ldml/characters/exemplarCharacters[@type=\"auxiliary\"]");

        private static final PathMatcher NUMBERING_SYSTEM =
            PathMatcher.of("ldml/numbers/defaultNumberingSystem");

        // These paths were mostly derived from looking at the previous implementation's behaviour
        // and can be modified as needed. Notably there are no "units" here (but they were also
        // excluded in the original code).
        private static final PathMatcher PSEUDO_PATHS = PathMatcher.anyOf(
            ldml("localeDisplayNames"),
            ldml("delimiters"),
            ldml("dates/calendars/calendar"),
            ldml("dates/fields"),
            ldml("dates/timeZoneNames"),
            ldml("listPatterns"),
            ldml("posix/messages"),
            ldml("characterLabels"),
            ldml("typographicNames"));

        // Paths which contain non-localizable data. It is important that these paths catch all the
        // non-localizable sub-paths of the list above. This list must be accurate.
        private static final PathMatcher EXCLUDE_PATHS = PathMatcher.anyOf(
            ldml("localeDisplayNames/localeDisplayPattern"),
            ldml("dates/timeZoneNames/fallbackFormat"));

        // The expectation is that all non-alias paths with values under these roots are "date/time
        // pattern like" (such as "E h:mm:ss B") in which care must be taken to not pseudo localize
        // the patterns in such as way as to break them. This list must be accurate.
        private static final PathMatcher PATTERN_PATHS = PathMatcher.anyOf(
            ldml("dates/calendars/calendar/timeFormats"),
            ldml("dates/calendars/calendar/dateFormats"),
            ldml("dates/calendars/calendar/dateTimeFormats"),
            ldml("dates/timeZoneNames/hourFormat"));

        private static PathMatcher ldml(String matcherSuffix) {
            return PathMatcher.of("ldml/" + matcherSuffix);
        }

        // Look for any attribute in the path with "narrow" in its value. Since "narrow" values
        // have strong expectations of width, we should not expand these (but might alter them
        // otherwise).
        private static final Predicate<String> IS_NARROW =
            Pattern.compile("\\[@[a-z]+=\"[^\"]*narrow[^\"]*\"]", CASE_INSENSITIVE).asPredicate();

        private static final Pattern NUMERIC_PLACEHOLDER = Pattern.compile("\\{\\d+\\}");
        private static final Pattern QUOTED_TEXT = Pattern.compile("'.*?'");

        private final PseudoType type;
        private final boolean isResolved;

        private PseudoLocaleData(CldrData srcData, CldrResolution resolution, PseudoType type) {
            super(srcData);
            this.isResolved = checkNotNull(resolution) == RESOLVED;
            this.type = checkNotNull(type);
        }

        @Override
        protected CldrValue filter(CldrValue value) {
            CldrPath path = value.getPath();

            // Special case(s) first...
            // We add the exemplar character list according to the pseudo type.
            if (AUX_EXEMPLARS.matches(path)) {
                return getExemplarValue(path);
            }
            // Force "latn" for the "ar_XB" pseudo locale (since otherwise it inherits from "ar".
            // The path we get here was from "en" so should already be "latn", but we just have
            // to return it in order for it to take effect.
            if (type == PseudoType.BIDI && NUMBERING_SYSTEM.matches(path)) {
                checkArgument(value.getValue().equals("latn"));
                return value;
            }

            CldrValue defaultReturnValue = isResolved ? value : null;
            // This makes it look like we have explicit values only for the included paths.
            if (!PSEUDO_PATHS.matchesPrefixOf(path) || EXCLUDE_PATHS.matchesPrefixOf(path)) {
                return defaultReturnValue;
            }
            String fullPath = value.getFullPath();
            // For now don't do anything with "narrow" data (this matches the previous behaviour).
            // We can always add something here later if necessary.
            if (IS_NARROW.test(fullPath)) {
                return defaultReturnValue;
            }
            String text = createMessage(value.getValue(), PATTERN_PATHS.matchesPrefixOf(path));
            return CldrValue.parseValue(fullPath, text);
        }

        // It's tempting to think that the existing exemplar list in "en" could be parsed to
        // generate list automatically (rather than having a hard coded list in the type) but
        // https://unicode.org/reports/tr35/tr35-general.html#ExemplarSyntax
        // makes it quite clear that this is infeasible, since there are many equivalent
        // representations of the examplar characters that could appear in the value
        // (e.g. "[a b ... z]", "[a-z]", "[{a} {b} ... {z}]")
        private CldrValue getExemplarValue(CldrPath path) {
            StringBuilder exemplarList = new StringBuilder("[");
            type.getExemplars().codePoints()
                .forEach(cp -> exemplarList.appendCodePoint(cp).append(' '));
            exemplarList.setCharAt(exemplarList.length() - 1, ']');
            return CldrValue.parseValue(path.toString(), exemplarList.toString());
        }

        private String createMessage(String text, boolean isPattern) {
            // Pattern text is split by the quoted sections (which are localizable) whereas
            // non-pattern text is split by placeholder (e.g. {0}) which are not localizable.
            // This is why "isPattern" is used to signal "isLocalizable" in addFragment().
            Matcher match = (isPattern ? QUOTED_TEXT : NUMERIC_PLACEHOLDER).matcher(text);
            // Alternate between unmatched and matched sections in the text, always localizing one
            // but not the other (depending the type). Append the trailing section at the end.
            PseudoText out = type.getText(isPattern);
            int start = 0;
            for (; match.find(); start = match.end()) {
                out.addFragment(text.substring(start, match.start()), !isPattern);
                out.addFragment(match.group(), isPattern);
            }
            out.addFragment(text.substring(start), !isPattern);
            return out.toString();
        }
    }

    // ---- Expanding Pseudo-localizer (e.g. "November" --> "[Ñöṽéɱƀéŕ one two]") ----

    // A map from a string of alternating key/value code-points; e.g. '1' -> '①'.
    // Note that a subset of this is also used to form the "exemplar" set (see PseudoType).
    private static final IntUnaryOperator CONVERT_CODEPOINT = toCodePointFunction(
        " \u2003!\u00a1\"\u2033#\u266f$\u20ac%\u2030&\u214b*\u204e+\u207a,\u060c-\u2010.\u00b7"
            + "/\u20440\u24ea1\u24602\u24613\u24624\u24635\u24646\u24657\u24668\u24679\u2468"
            + ":\u2236;\u204f<\u2264=\u2242>\u2265?\u00bf@\u055eA\u00c5B\u0181C\u00c7D\u00d0"
            + "E\u00c9F\u0191G\u011cH\u0124I\u00ceJ\u0134K\u0136L\u013bM\u1e40N\u00d1O\u00d6"
            + "P\u00deQ\u01eaR\u0154S\u0160T\u0162U\u00dbV\u1e7cW\u0174X\u1e8aY\u00ddZ\u017d"
            + "[\u2045\\\u2216]\u2046^\u02c4_\u203f`\u2035a\u00e5b\u0180c\u00e7d\u00f0e\u00e9"
            + "f\u0192g\u011dh\u0125i\u00eej\u0135k\u0137l\u013cm\u0271n\u00f1o\u00f6p\u00fe"
            + "q\u01ebr\u0155s\u0161t\u0163u\u00fbv\u1e7dw\u0175x\u1e8by\u00fdz\u017e|\u00a6"
            + "~\u02de");

    // Converts a source/target alternating code-points into a map.
    private static IntUnaryOperator toCodePointFunction(String s) {
        // Not pretty, but there's no nice way to "pair up" successive stream elements without
        // extra library dependencies, so we collect them and then iterate via index.
        int[] codePoints = s.codePoints().toArray();
        checkArgument((codePoints.length & 1) == 0,
            "must have an even number of code points (was %s)", codePoints.length);
        ImmutableMap<Integer, Integer> map =
            IntStream.range(0, codePoints.length / 2)
                .boxed()
                .collect(toImmutableMap(n -> codePoints[2 * n], n -> codePoints[(2 * n) + 1]));
        return cp -> map.getOrDefault(cp, cp);
    }

    // A list of words to be added to text when it is expanded. A whole number of words are
    // always added (and the fact they are numeric words is irrelevant, could be Lorem Ipsum).
    // So far nothing goes above "ten" in en_XA, but this can always be trivially extended.
    private static final String PADDING = "one two three four five six seven eight nine ten";

    private static PseudoText expanding(boolean isPattern) {
        return new PseudoText() {
            IntStream.Builder codePoints = IntStream.builder();

            @Override
            public void addFragment(String text, boolean isLocalizable) {
                text.codePoints()
                    .map(isLocalizable ? CONVERT_CODEPOINT : cp -> cp)
                    .forEach(codePoints::add);
            }

            @Override
            public String toString() {
                int[] cp = codePoints.build().toArray();
                // Copy the original code and round up the 50% calculation (it's not important).
                int endIndex = CharMatcher.whitespace().indexIn(PADDING, (cp.length + 1) / 2);
                String suffix = PADDING.substring(0, Math.min(endIndex, PADDING.length()));
                // For pattern strings, any literal text must be quoted (the fragment text
                // already was). Note that this is why we don't transform single-quotes.
                if (isPattern) {
                    suffix = "'" + suffix.replace(" ", "' '") + "'";
                }
                // Final output is something like "November" --> "[Ñöṽéɱƀéŕ one two]"
                // Where the additional padding adds at least 50% to the length of the text.
                return "[" + new String(cp, 0, cp.length) + " " + suffix + "]";
            }
        };
    }

    // ---- Bidi Pseudo-localizer (e.g. "November" --> "rebmevoN" using BiDi tags)----

    // Right-to-left override character.
    private static final String RLO = "\u202e";
    // Arabic letter mark character.
    private static final String ALM = "\u061C";
    // Pop direction formatting character.
    private static final String PDF = "\u202c";
    // Prefix to add before each LTR word.
    private static final String BIDI_PREFIX = ALM + RLO;
    // Postfix to add after each LTR word.
    private static final String BIDI_POSTFIX = PDF + ALM;

    // Bidi localization doesn't care if the fragment is a pattern or not.
    @SuppressWarnings("unused")
    private static PseudoText bidi(boolean isPattern) {
        return new PseudoText() {
            private final StringBuilder out = new StringBuilder();

            // This was largely copied from the original CLDRFilePseudolocalizer class and
            // while it appears to work fine, I don't know enough to comment it clearly.
            // TODO: Find someone who can add a decent comment here!
            @Override
            public void addFragment(String text, boolean isLocalizable) {
                if (isLocalizable) {
                    boolean wrapping = false;
                    for (int index = 0; index < text.length();) {
                        int codePoint = text.codePointAt(index);
                        index += Character.charCount(codePoint);
                        byte directionality = Character.getDirectionality(codePoint);
                        boolean needsWrap = (directionality == DIRECTIONALITY_LEFT_TO_RIGHT);
                        if (needsWrap != wrapping) {
                            wrapping = needsWrap;
                            out.append(wrapping ? BIDI_PREFIX : BIDI_POSTFIX);
                        }
                        out.appendCodePoint(codePoint);
                    }
                    if (wrapping) {
                        out.append(BIDI_POSTFIX);
                    }
                } else {
                    out.append(text);
                }
            }

            @Override
            public String toString() {
                return out.toString();
            }
        };
    }

    private PseudoLocales() {}
}
