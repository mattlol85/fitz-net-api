package org.fitznet.fitznetapi.model;

/**
 * A chapter of a recipe book, embedded in {@link RecipeBook#getChapters()} (the list order is the
 * book order).
 *
 * @param id server-generated short id, unique within the book
 * @param name at most 40 characters
 * @param tabColor the colour name of the chapter's index tab
 */
public record RecipeChapter(String id, String name, String tabColor) {}
