package com.sixbean.testpike.core.favorite.domain.component;


public record FavoriteEntitySpec(String type, String table, String titleColumn,
                                String subtitleColumn, String deletedFilter) {
}
