import { zipSync, unzipSync } from "./vendor/fflate.js";
import { SCHEMA_VERSION, validateLedger } from "./model.js";
import { loadLedger, replaceLedger } from "./storage.js";

const format = "simple-ledger-monthly";
const archiveLimit = 180 * 1024 * 1024;
const monthFile = /^entries\/\d{4}-(0[1-9]|1[0-2])\.jsonl$/;
const encoder = new TextEncoder(), decoder = new TextDecoder("utf-8", { fatal: true });
const encode = value => encoder.encode(JSON.stringify(value));

async function digest(bytes) {
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, "0")).join("");
}

export async function packLedger(data) {
  const { categories, budgets, asset, preferences } = data;
  const metadata = encode({ schemaVersion: SCHEMA_VERSION, categories, budgets, asset, preferences });
  const groups = new Map();
  for (const entry of data.entries) {
    const month = entry.businessDate.slice(0, 7);
    if (!groups.has(month)) groups.set(month, []);
    groups.get(month).push(JSON.stringify(entry));
  }
  const files = { "ledger.json": metadata };
  const shards = [];
  for (const [month, lines] of [...groups].sort(([a], [b]) => a.localeCompare(b))) {
    const name = `entries/${month}.jsonl`;
    const bytes = encoder.encode(lines.join("\n") + "\n");
    files[name] = bytes;
    shards.push({ name, count: lines.length, sha256: await digest(bytes) });
  }
  files["manifest.json"] = encode({ format, version: 1, count: data.entries.length, ledgerSha256: await digest(metadata), shards });
  if (Object.values(files).reduce((size, bytes) => size + bytes.byteLength, 0) > archiveLimit) throw new Error("账本备份超过容量限制");
  return zipSync(files, { level: 6 });
}

export async function unpackLedger(buffer) {
  const bytes = new Uint8Array(buffer);
  if (bytes.byteLength > archiveLimit) throw new Error("备份文件过大");
  if (bytes[0] !== 80 || bytes[1] !== 75) return validateLedger(JSON.parse(decoder.decode(bytes)));
  let size = 0;
  const seen = new Set();
  const files = unzipSync(bytes, { filter: ({ name, originalSize, size: compressedSize, compression }) => {
    if ((name !== "manifest.json" && name !== "ledger.json" && !monthFile.test(name)) || seen.has(name)) {
      throw new Error("备份压缩包内含未知或重复文件");
    }
    seen.add(name);
    if (!Number.isSafeInteger(originalSize) || originalSize < 0 || (compression === 0 && originalSize !== compressedSize)) {
      throw new Error("备份分片大小无效");
    }
    size += originalSize;
    if (size > archiveLimit || seen.size > 4000) throw new Error("备份解压后超过容量限制");
    return true;
  } });
  if (!files["manifest.json"] || !files["ledger.json"]) throw new Error("缺少爱记账备份清单或账本资料");
  const manifest = JSON.parse(decoder.decode(files["manifest.json"]));
  if (manifest?.format !== format || manifest.version !== 1 || !Array.isArray(manifest.shards) ||
    !Number.isInteger(manifest.count) || manifest.count < 0 || manifest.count > 120000 ||
    manifest.shards.length !== seen.size - 2 ||
    manifest.ledgerSha256 !== await digest(files["ledger.json"])) throw new Error("备份版本或校验值无效");
  const entries = [];
  seen.clear();
  for (const shard of manifest.shards) {
    if (typeof shard?.name !== "string" || !monthFile.test(shard.name) || seen.has(shard.name)) throw new Error("备份分片清单无效");
    seen.add(shard.name);
    const file = files[shard.name];
    if (!file || shard.sha256 !== await digest(file)) throw new Error("备份分片校验失败");
    const lines = decoder.decode(file).trimEnd().split("\n");
    if (lines.length !== shard.count || entries.length + lines.length > manifest.count) throw new Error("备份分片笔数不符");
    for (const line of lines) {
      const entry = JSON.parse(line);
      if (entry?.businessDate?.slice(0, 7) !== shard.name.slice(8, 15)) throw new Error("分片中存在错误月份的流水");
      entries.push(entry);
    }
  }
  if (entries.length !== manifest.count) throw new Error("备份总笔数不符");
  return validateLedger({ ...JSON.parse(decoder.decode(files["ledger.json"])), entries });
}

if (typeof WorkerGlobalScope !== "undefined" && self instanceof WorkerGlobalScope) {
  self.onmessage = async ({ data: { action, payload } }) => {
    try {
      if (action === "pack" || action === "json") {
        const { entryCount, monthlyStats, ...ledger } = await loadLedger();
        if (action === "json") {
          self.postMessage({ result: JSON.stringify(ledger) });
        } else {
          const result = await packLedger(ledger);
          self.postMessage({ result }, [result.buffer]);
        }
      } else if (action === "unpack") {
        self.postMessage({ result: await unpackLedger(await payload.arrayBuffer()) });
      } else if (action === "restore") {
        await replaceLedger(payload);
        self.postMessage({ result: true });
      } else {
        throw new Error("备份操作无效");
      }
    } catch (error) {
      self.postMessage({ error: error.message });
    }
  };
}
