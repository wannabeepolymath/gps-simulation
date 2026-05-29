import "dotenv/config";
import { readdir, readFile } from "node:fs/promises";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { pool } from "./db.js";

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = resolve(here, "../../migrations");

export async function runMigrations(): Promise<void> {
    const client = await pool.connect();
    try {
        await client.query(`
            CREATE TABLE IF NOT EXISTS _migrations (
                name TEXT PRIMARY KEY,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
        `);

        const applied = new Set<string>(
            (await client.query<{ name: string }>("SELECT name FROM _migrations")).rows.map(
                (r) => r.name,
            ),
        );

        const files = (await readdir(migrationsDir))
            .filter((f) => f.endsWith(".sql"))
            .sort();

        for (const file of files) {
            if (applied.has(file)) continue;
            const sql = await readFile(join(migrationsDir, file), "utf-8");
            console.log(`[migrate] applying ${file}`);
            await client.query("BEGIN");
            try {
                await client.query(sql);
                await client.query("INSERT INTO _migrations (name) VALUES ($1)", [file]);
                await client.query("COMMIT");
            } catch (e) {
                await client.query("ROLLBACK");
                throw e;
            }
        }
        console.log(`[migrate] up to date (${files.length} files seen, ${applied.size} previously applied)`);
    } finally {
        client.release();
    }
}

if (import.meta.url === `file://${process.argv[1]}`) {
    runMigrations()
        .then(() => process.exit(0))
        .catch((e) => {
            console.error("[migrate] failed", e);
            process.exit(1);
        });
}
