(function (host) {
    "use strict";

    var MinecraftServer = Packages.net.minecraft.server.MinecraftServer;
    host.log("SoulHearts.js loaded; MinecraftServer is available: " +
        (MinecraftServer !== null));
})(SoulHostRuntime);