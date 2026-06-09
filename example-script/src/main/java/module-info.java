module com.botwithus.bot.scripts.example {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.quest;
    requires imgui.binding;

    provides com.botwithus.bot.api.BotScript
        with com.botwithus.bot.scripts.example.ExampleScript,
             com.botwithus.bot.scripts.example.WoodcuttingFletcherScript,
             com.botwithus.bot.scripts.example.WalkToFlagScript,
             com.botwithus.bot.scripts.example.DivinationScript,
             com.botwithus.bot.scripts.example.LocationProbeScript,
             com.botwithus.bot.scripts.example.CooksAssistantScript;
}
