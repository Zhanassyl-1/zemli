package com.zemli.bot.model;

public record MarketListing(
        long id,
        long sellerId,
        String itemType,
        int price,
        boolean auction,
        Long auctionEndsAt,
        Long highestBidderId,
        Integer highestBid
) {
}
