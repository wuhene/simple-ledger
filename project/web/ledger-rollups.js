import { INCOME, localToday, shiftDate } from "./model.js";

export function applyEntry(months, entry, delta) {
  const month = entry.businessDate.slice(0, 7);
  const summary = months.get(month) ?? { month, count: 0, income: 0, expense: 0, days: Array(31).fill(0) };
  const side = entry.type === INCOME ? "income" : "expense";
  summary[side] += entry.amountMinor * delta;
  summary.count += delta;
  summary.days[Number(entry.businessDate.slice(8, 10)) - 1] += delta;
  months.set(month, summary);
}

export function summarizeRollups(months) {
  let income = 0, expense = 0;
  for (const month of months) {
    income += month.income;
    expense += month.expense;
  }
  return { income, expense, balance: income - expense };
}

export function rollupAchievements(months, today = localToday()) {
  const dates = new Set();
  let count = 0;
  for (const month of months) {
    count += month.count;
    month.days.forEach((value, i) => {
      if (value > 0) dates.add(`${month.month}-${String(i + 1).padStart(2, "0")}`);
    });
  }
  let cursor = dates.has(today) ? today : shiftDate(today, -1), streak = 0;
  while (dates.has(cursor)) {
    streak++;
    cursor = shiftDate(cursor, -1);
  }
  return { days: dates.size, count, streak };
}
