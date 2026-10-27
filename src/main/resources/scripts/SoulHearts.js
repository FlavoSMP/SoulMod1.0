(function (host) {
    "use strict";

    var rules = {
        startingHearts: 10,
        maximumHearts: 20,
        ghostHearts: 10,
        diceThreshold: 5,
        strengthThreshold: 10,
        goldenHeartsThreshold: 15,
        speedThreshold: 20,
        effectDurationTicks: 200
    };

    host.registerSoulHearts(
        rules.startingHearts,
        rules.maximumHearts,
        rules.ghostHearts,
        rules.diceThreshold,
        rules.strengthThreshold,
        rules.goldenHeartsThreshold,
        rules.speedThreshold,
        rules.effectDurationTicks
    );
    host.log("SoulHearts rules registered");
})(SoulHostRuntime);