import { mkdir, writeFile } from "node:fs/promises";
import { packLedger } from "../web/ledger-backup.js";
import { initialCategories, shiftDate } from "../web/model.js";

const first = "2011-11-26";
const last = "2026-11-25";
const days = (Date.parse(last) - Date.parse(first)) / 86400000 + 1;
const dates = Array.from({ length: days }, (_, index) => shiftDate(first, index));
const examples = [
  [0, "午餐叉烧饭"], [1, "网购保温杯"], [2, "厨房清洁用品"], [3, "地铁通勤"],
  [4, "周末菜市场"], [5, "水果店苹果"], [8, "周末电影票"], [12, "房屋月租"],
  [17, "周末高铁票"], [21, "社区门诊挂号"], [22, "书店买书"], [27, "打印办公材料"],
];
const entries = Array.from({ length: 100000 }, (_, index) => {
  const date = dates[index % days];
  const hour = String(Math.floor(index / days) % 18).padStart(2, "0");
  const timestamp = date + "T" + hour + ":00:00.000Z";
  const duck = index % 50 === 0;
  const income = index % 10 === 1;
  const [expenseCategory, expenseNote] = examples[(index * 7 + Math.floor(index / days)) % examples.length];
  const incomeCategory = Math.floor(index / 10) % 3;
  const note = duck ? "四季民福王府井店烤鸭" : income ? ["工资到账", "兼职项目收款", "理财收益"][incomeCategory] : expenseNote;
  return {
    id: "acceptance-" + index,
    type: income ? "INCOME" : "EXPENSE",
    categoryId: income ? "income-" + incomeCategory : "expense-" + (duck ? 0 : expenseCategory),
    businessDate: date,
    amountMinor: duck ? 8800 + index % 25000 : income ? 5000 + index % 500000 : 100 + index % 15000,
    note: "【压测】" + note,
    createdAt: timestamp,
    updatedAt: timestamp,
    ...(duck ? {
      sourceText: "在北京王府井吃了四季民福的烤鸭",
      clues: [
        { type: "城市", value: "北京" },
        { type: "地点", value: "王府井" },
        { type: "商家", value: "四季民福" },
        { type: "菜品", value: "烤鸭" },
      ],
      location: { latitude: 39.915, longitude: 116.412, accuracy: 25, capturedAt: timestamp },
    } : {}),
  };
});

const month = last.slice(0, 7);
const updatedAt = last + "T00:00:00.000Z";
const archive = await packLedger({
  schemaVersion: 1, categories: initialCategories(), entries,
  budgets: [{ yearMonth: month, amountMinor: 1500000, updatedAt }],
  asset: { assetMinor: 66550000, liabilityMinor: 22000000, updatedAt },
  preferences: { hideAmountsByDefault: false },
});
const assets = new URL("./app/src/acceptance/assets/", import.meta.url);
await mkdir(assets, { recursive: true });
await writeFile(new URL("acceptance-ledger.zip", assets), archive);
console.log(entries.length + " 笔合成流水，" + first + " 至 " + last);
