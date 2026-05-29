import "dotenv/config";
import express from "express";
import gpxRouter from "./routes/gpx.js";
import { runMigrations } from "./lib/migrate.js";

const app = express();

app.use(express.json({ limit: "1mb" }));

app.get("/health", (_req, res) => {
    res.json({ ok: true });
});

app.use("/gpx", gpxRouter);

app.use((err: unknown, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    console.error("[error]", err);
    if (res.headersSent) return;
    res.status(500).json({ error: "internal error" });
});

const port = Number(process.env.PORT ?? 4000);

runMigrations()
    .then(() => {
        app.listen(port, () => {
            console.log(`[server] listening on http://0.0.0.0:${port}`);
        });
    })
    .catch((e) => {
        console.error("[server] migrations failed; aborting startup", e);
        process.exit(1);
    });
