package dev.warehouse.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.export.Importer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /warehouse export, import, clear */
public final class DataCommands {
    private static String pendingClear;
    private static long pendingClearAtMs;

    private DataCommands() {}

    private static final SuggestionProvider<FabricClientCommandSource> EXPORT_FILES =
            (c, b) -> SharedSuggestionProvider.suggest(WarehouseClient.importer().availableFiles(), b);

    public static void attach(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        root.then(literal("export")
                .executes(c -> export(null, false))
                .then(literal("withlost").executes(c -> export(null, true)))
                .then(argument("name", StringArgumentType.greedyString()).executes(c -> export(StringArgumentType.getString(c, "name"), false))));

        root.then(literal("import")
                .executes(c -> {
                    var files = WarehouseClient.importer().availableFiles();
                    if (files.isEmpty()) Chat.info("No exports in " + WarehouseClient.storage().exportsDir());
                    else {
                        Chat.info("Exports (" + WarehouseClient.storage().exportsDir() + "):");
                        for (String f : files) Chat.info("  " + f);
                        Chat.info("Use /warehouse import <file> [merge|replace] (default merge).");
                    }
                    return 1;
                })
                .then(argument("file", StringArgumentType.string()).suggests(EXPORT_FILES)
                        .executes(c -> doImport(StringArgumentType.getString(c, "file"), Importer.Mode.MERGE))
                        .then(literal("merge").executes(c -> doImport(StringArgumentType.getString(c, "file"), Importer.Mode.MERGE)))
                        .then(literal("replace").executes(c -> doImport(StringArgumentType.getString(c, "file"), Importer.Mode.REPLACE)))));

        root.then(literal("clear")
                .then(literal("index").executes(c -> clear("index")))
                .then(literal("lost").executes(c -> clear("lost")))
                .then(literal("plan").executes(c -> clear("plan")))
                .then(literal("items").executes(c -> clear("items")))
                .then(literal("all").executes(c -> clear("all")))
                .then(literal("confirm").executes(c -> confirmClear())));
    }

    private static int export(String name, boolean withLost) {
        String f = WarehouseClient.exporter().export(name, withLost);
        if (f == null) {
            Chat.error("Export failed (see log).");
            return 0;
        }
        Chat.info("Exported to " + WarehouseClient.storage().exportsDir().resolve(f));
        return 1;
    }

    private static int doImport(String file, Importer.Mode mode) {
        Importer.Result r = WarehouseClient.importer().run(file, mode);
        if (r.ok()) {
            Chat.info(r.message());
            WarehouseClient.onRegionsChanged();
            return 1;
        }
        Chat.error(r.message());
        return 0;
    }

    private static int clear(String what) {
        pendingClear = what;
        pendingClearAtMs = System.currentTimeMillis();
        Chat.warn("This will erase the " + (what.equals("all") ? "index, lost log, plan and item groups" : what) + " for this server. Run /warehouse clear confirm within 15 s to proceed.");
        return 1;
    }

    private static int confirmClear() {
        if (pendingClear == null || System.currentTimeMillis() - pendingClearAtMs > 15000) {
            pendingClear = null;
            Chat.error("Nothing to confirm. Run /warehouse clear <index|lost|plan|items|all> first.");
            return 0;
        }
        String what = pendingClear;
        pendingClear = null;
        var st = WarehouseClient.storage();
        if (what.equals("index") || what.equals("all")) WarehouseClient.index().clear();
        if (what.equals("lost") || what.equals("all")) WarehouseClient.lostLog().clear();
        if (what.equals("plan") || what.equals("all")) WarehouseClient.organizer().clearPlan();
        if (what.equals("items") || what.equals("all")) {
            st.items.get().clear();
            st.items.markDirty();
            WarehouseClient.itemGroups().invalidate();
            WarehouseClient.categories().invalidate();
        }
        st.flushAll();
        Chat.info("Cleared " + what + ".");
        return 1;
    }
}
