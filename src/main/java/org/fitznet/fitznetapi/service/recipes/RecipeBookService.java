package org.fitznet.fitznetapi.service.recipes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fitznet.fitznetapi.dto.requests.RecipeChapterRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateRecipeBookRequestDto;
import org.fitznet.fitznetapi.dto.responses.RecipeBookDto;
import org.fitznet.fitznetapi.dto.responses.RecipeChapterDto;
import org.fitznet.fitznetapi.model.RecipeBook;
import org.fitznet.fitznetapi.model.RecipeChapter;
import org.fitznet.fitznetapi.repository.RecipeBookRepository;
import org.fitznet.fitznetapi.repository.RecipeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's recipe book: one per user, created on first use with the default chapters. A PUT
 * replaces the cover and the whole chapter list; recipes of a removed chapter move to Unsorted.
 */
@Service
public class RecipeBookService {

  private static final Logger logger = LoggerFactory.getLogger(RecipeBookService.class);

  public static final String DEFAULT_COVER_COLOR = "rose";
  public static final int MAX_TITLE_CHARS = 60;
  private static final String TITLE_SUFFIX = "'s Recipe Book";

  /** Seeded into every new book, in this order: {name, tabColor}. */
  static final List<String[]> DEFAULT_CHAPTERS =
      List.of(
          new String[] {"Breakfast", "amber"},
          new String[] {"Soups & Salads", "sage"},
          new String[] {"Mains", "rose"},
          new String[] {"Sides", "teal"},
          new String[] {"Baking", "coral"},
          new String[] {"Desserts", "plum"},
          new String[] {"Drinks", "sky"});

  private final RecipeBookRepository books;
  private final RecipeRepository recipes;

  public RecipeBookService(RecipeBookRepository books, RecipeRepository recipes) {
    this.books = books;
    this.recipes = recipes;
  }

  /** The caller's book, created with the default chapters if they have none yet. */
  public RecipeBook getOrCreate(String username) {
    return books.findByOwnerUsername(username).orElseGet(() -> create(username));
  }

  public RecipeBookDto getBook(String username) {
    return toDto(getOrCreate(username));
  }

  public RecipeBookDto update(String username, UpdateRecipeBookRequestDto request) {
    RecipeBook book = getOrCreate(username);
    Set<String> existing = new HashSet<>(chapterIds(book));
    Set<String> used = new HashSet<>(existing);
    Set<String> kept = new LinkedHashSet<>();
    List<RecipeChapter> chapters = new ArrayList<>();
    for (RecipeChapterRequestDto chapter : request.getChapters()) {
      String id = blankToNull(chapter.getId());
      if (id != null && existing.contains(id)) {
        if (!kept.add(id)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate chapter id " + id);
        }
      } else {
        id = newChapterId(used);
      }
      used.add(id);
      chapters.add(new RecipeChapter(id, chapter.getName().trim(), chapter.getTabColor()));
    }
    Set<String> removed = new HashSet<>(existing);
    removed.removeAll(kept);

    book.setTitle(request.getTitle().trim());
    book.setCoverColor(request.getCoverColor());
    book.setChapters(chapters);
    book.setUpdatedAt(now());
    RecipeBook saved = books.save(book);
    long moved = recipes.unsetChapter(username, removed);
    if (!removed.isEmpty()) {
      logger.info("Removed {} chapter(s) from book {}; {} recipe(s) moved to Unsorted", removed.size(), saved.getId(), moved);
    }
    return toDto(saved);
  }

  /** Is {@code chapterId} one of the book's chapters? */
  public static boolean hasChapter(RecipeBook book, String chapterId) {
    return chapterIds(book).contains(chapterId);
  }

  private static List<String> chapterIds(RecipeBook book) {
    return book.getChapters() == null
        ? List.of()
        : book.getChapters().stream().map(RecipeChapter::id).toList();
  }

  private RecipeBook create(String username) {
    Instant now = now();
    Set<String> used = new HashSet<>();
    List<RecipeChapter> chapters = new ArrayList<>();
    for (String[] chapter : DEFAULT_CHAPTERS) {
      String id = newChapterId(used);
      used.add(id);
      chapters.add(new RecipeChapter(id, chapter[0], chapter[1]));
    }
    RecipeBook book =
        RecipeBook.builder()
            .ownerUsername(username)
            .title(defaultTitle(username))
            .coverColor(DEFAULT_COVER_COLOR)
            .chapters(chapters)
            .createdAt(now)
            .updatedAt(now)
            .build();
    try {
      RecipeBook saved = books.insert(book);
      logger.info("Created recipe book {}", saved.getId());
      return saved;
    } catch (DuplicateKeyException race) {
      // Two first loads at once: the unique owner index kept the other one; use it.
      return books.findByOwnerUsername(username).orElseThrow(() -> race);
    }
  }

  static String defaultTitle(String username) {
    int room = MAX_TITLE_CHARS - TITLE_SUFFIX.length();
    String name = username.length() > room ? username.substring(0, room) : username;
    return name + TITLE_SUFFIX;
  }

  private static String newChapterId(Set<String> used) {
    String id;
    do {
      id = UUID.randomUUID().toString().substring(0, 8);
    } while (used.contains(id));
    return id;
  }

  private RecipeBookDto toDto(RecipeBook book) {
    Map<String, Long> counts = recipes.countByChapter(book.getOwnerUsername());
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    List<RecipeChapterDto> chapters =
        book.getChapters() == null
            ? List.of()
            : book.getChapters().stream()
                .map(c -> new RecipeChapterDto(c.id(), c.name(), c.tabColor(), counts.getOrDefault(c.id(), 0L)))
                .toList();
    return new RecipeBookDto(
        book.getId(), book.getTitle(), book.getCoverColor(), chapters, total, book.getCreatedAt());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Mongo stores milliseconds; truncating keeps a write's response equal to later reads. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }
}
