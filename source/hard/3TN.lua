Config = {
    Team = "Pirates",
    Configuration = {
        HopWhenIdle = true,
        AutoHop = true,
        AutoHopDelay = 60 * 60,
        FpsBoost = false,
        blackscreen = true
    },
    Items = {
        -- Melees 
        AutoFullyMelees = true,
        -- Swords 
        Saber = true,
        CursedDualKatana = false,
        -- Guns 
        SoulGuitar = false,
        -- Upgrades 
        RaceV2 = true
    },
    Settings = {
        StayInSea2UntilHaveDarkFragments = false
    }
}
loadstring(game:HttpGet("https://raw.githubusercontent.com/hhl29042008-ops/script/refs/heads/main/cac"))()