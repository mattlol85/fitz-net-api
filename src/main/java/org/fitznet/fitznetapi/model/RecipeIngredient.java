package org.fitznet.fitznetapi.model;

/**
 * One ingredient row of a recipe, embedded in {@link Recipe#getIngredients()} in display order.
 * The quantity is kept as text ({@code "1 1/2"}), so recipes are never scaled.
 *
 * @param quantity optional, at most 20 characters
 * @param unit optional, at most 20 characters
 * @param name required, at most 80 characters
 * @param note optional, at most 120 characters (e.g. "sifted")
 */
public record RecipeIngredient(String quantity, String unit, String name, String note) {}
