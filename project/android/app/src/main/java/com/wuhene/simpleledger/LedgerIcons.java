package com.wuhene.simpleledger;

/**
 * 映射账本分类与原生矢量图标。
 *
 * @author chaoshunh
 * @date 2026/11/25
 */
final class LedgerIcons {
    private static final String[] EXPENSE = ("utensils shopping_bag package bus_front carrot apple cookie dumbbell gamepad_2 "
            + "smartphone shirt paintbrush house armchair baby accessibility users plane cigarette laptop car_front cross "
            + "book_open graduation_cap paw_print hand_coins gift briefcase_business ellipsis").split(" ");
    private static final String[] INCOME = "wallet badge_dollar_sign chart_no_axes_combined gift circle_dollar_sign credit_card messages_square".split(" ");

    /**
     * 根据分类及用户选择返回图标资源后缀。
     */
    static String graphic(LedgerDb.Category category) {
        if (category == null) return "ellipsis";
        String key = category.iconKey;
        if (key == null) key = "other";
        if (category.builtin && key.equals(LedgerDb.iconFor(category.name))) {
            boolean expense = LedgerDb.EXPENSE.equals(category.type);
            String prefix = expense ? "expense-" : "income-";
            String[] choices = expense ? EXPENSE : INCOME;
            if (category.id.startsWith(prefix)) {
                try {
                    int index = Integer.parseInt(category.id.substring(prefix.length()));
                    if (index >= 0 && index < choices.length) return choices[index];
                } catch (NumberFormatException ignored) {}
            }
        }
        switch (key) {
            case "food": return "utensils";
            case "bag": return "shopping_bag";
            case "home": return "house";
            case "bus": return "bus_front";
            case "book": return "book_open";
            case "briefcase": return "briefcase_business";
            case "medical": return "cross";
            case "pet": return "paw_print";
            case "car": return "car_front";
            case "other": return "ellipsis";
            default: return key.replace('-', '_');
        }
    }
}
