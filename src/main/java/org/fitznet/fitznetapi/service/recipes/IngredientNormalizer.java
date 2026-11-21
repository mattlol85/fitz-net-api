package org.fitznet.fitznetapi.service.recipes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns ingredient names into the derived search fields of a recipe, and search input into the
 * same form, so "Eggs" finds "egg" and "tomatoes" finds "Tomato":
 *
 * <ul>
 *   <li>lowercase, drop apostrophes, every other run of punctuation becomes a space, whitespace is
 *       collapsed;
 *   <li>the last word is lightly singularized ({@code berries -> berry}, {@code tomatoes -> tomato},
 *       {@code peaches -> peach}, {@code eggs -> egg}; words ending in {@code ss}, {@code us} or
 *       {@code is} and words shorter than four letters are kept).
 * </ul>
 *
 * The full normalized names feed the ingredient index; the terms (names, plus every singularized
 * word of at least three letters and every run of consecutive words) are what search matches.
 */
public final class IngredientNormalizer {

  private static final Pattern APOSTROPHES = Pattern.compile("['‘’`]");
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final int MIN_WORD_TERM = 3;
  /** Sub-phrases are only built from the first words of a (rarely) very long name. */
  private static final int MAX_PHRASE_WORDS = 12;

  /** Plurals the suffix rules would get wrong. */
  private static final Map<String, String> IRREGULAR =
      Map.ofEntries(
          Map.entry("leaves", "leaf"),
          Map.entry("loaves", "loaf"),
          Map.entry("halves", "half"),
          Map.entry("knives", "knife"),
          Map.entry("chilies", "chili"),
          Map.entry("chillies", "chilli"),
          Map.entry("cookies", "cookie"),
          Map.entry("pies", "pie"),
          Map.entry("brownies", "brownie"),
          Map.entry("veggies", "veggie"),
          Map.entry("smoothies", "smoothie"),
          Map.entry("calories", "calorie"));

  /** Words that only look plural. */
  private static final Set<String> INVARIANT =
      Set.of("molasses", "series", "species", "swiss", "grits", "hummus", "couscous", "asparagus");

  private IngredientNormalizer() {}

  /** The normalized form of one ingredient name or search term; empty when nothing is left. */
  public static String normalize(String raw) {
    List<String> words = words(raw);
    if (words.isEmpty()) {
      return "";
    }
    int last = words.size() - 1;
    words.set(last, singularize(words.get(last)));
    return String.join(" ", words);
  }

  /** The distinct normalized names, in the order given; names that normalize to nothing are skipped. */
  public static List<String> names(Collection<String> rawNames) {
    Set<String> names = new LinkedHashSet<>();
    for (String raw : rawNames) {
      String name = normalize(raw);
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return new ArrayList<>(names);
  }

  /**
   * The search terms of already normalized names: each name, each of its singularized words of at
   * least three letters, and each run of two or more consecutive singularized words.
   */
  public static List<String> terms(Collection<String> normalizedNames) {
    Set<String> terms = new LinkedHashSet<>();
    for (String name : normalizedNames) {
      if (name.isEmpty()) {
        continue;
      }
      terms.add(name);
      List<String> words = Arrays.stream(name.split(" ")).map(IngredientNormalizer::singularize).toList();
      for (String word : words) {
        if (word.length() >= MIN_WORD_TERM) {
          terms.add(word);
        }
      }
      int n = Math.min(words.size(), MAX_PHRASE_WORDS);
      for (int from = 0; from < n; from++) {
        for (int to = from + 2; to <= n; to++) {
          terms.add(String.join(" ", words.subList(from, to)));
        }
      }
    }
    return new ArrayList<>(terms);
  }

  static String singularize(String word) {
    if (word.length() < 4 || INVARIANT.contains(word)) {
      return word;
    }
    String irregular = IRREGULAR.get(word);
    if (irregular != null) {
      return irregular;
    }
    if (word.endsWith("ies") && word.length() > 4) {
      return word.substring(0, word.length() - 3) + "y";
    }
    if (word.endsWith("oes")
        || word.endsWith("sses")
        || word.endsWith("shes")
        || word.endsWith("ches")
        || word.endsWith("xes")) {
      return word.substring(0, word.length() - 2);
    }
    if (word.endsWith("ss") || word.endsWith("us") || word.endsWith("is")) {
      return word;
    }
    if (word.endsWith("s")) {
      return word.substring(0, word.length() - 1);
    }
    return word;
  }

  private static List<String> words(String raw) {
    if (raw == null) {
      return new ArrayList<>();
    }
    String cleaned = APOSTROPHES.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("");
    cleaned = NON_WORD.matcher(cleaned).replaceAll(" ").trim();
    if (cleaned.isEmpty()) {
      return new ArrayList<>();
    }
    return new ArrayList<>(Arrays.asList(cleaned.split(" ")));
  }
}
