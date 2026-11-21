package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.List;

/**
 * Black Market Poster
 *
 * Loop: hold an item -> run the "post" command -> wait N seconds -> run the "cancel" command -> repeat.
 * The commands are plain text settings, so they can be changed for every server.
 *
 * Placeholders usable in the post command:
 *   {price}  - price (default price, or a per-item override)
 *   {amount} - the "amount" setting (leave it out of the command if the server sells the whole held stack)
 */
public class BlackMarketPoster extends Module {
    private enum State { POST_PREPARE, POST_SEND, WAITING, CANCELLING, COOLDOWN }

    private final SettingGroup sgItems = settings.createGroup("Items");
    private final SettingGroup sgCommands = settings.createGroup("Commands");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgLoop = settings.createGroup("Loop");

    // ---- Items ----
    private final ItemListSetting items = sgItems.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items that will be posted. Every matching stack in your inventory is posted.")
        .build()
    );

    private final StringSetting defaultPrice = sgItems.add(new StringSetting.Builder()
        .name("default-price")
        .description("Price used for {price} when an item has no override.")
        .defaultValue("1000")
        .build()
    );

    private final StringListSetting priceOverrides = sgItems.add(new StringListSetting.Builder()
        .name("price-overrides")
        .description("Per-item price, one entry per line as 'Item Name=price' (e.g. 'Diamond=500'). Uses the item's display name.")
        .defaultValue(List.of())
        .build()
    );

    private final IntSetting amount = sgItems.add(new IntSetting.Builder()
        .name("amount")
        .description("Value used for {amount} in the post command.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final IntSetting hotbarSlot = sgItems.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot (1-9) used to hold the item that gets posted.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    // ---- Commands ----
    private final StringSetting postCommand = sgCommands.add(new StringSetting.Builder()
        .name("post-command")
        .description("Command that lists the held item on the black market. Placeholders: {price}, {amount}.")
        .defaultValue("/bm sell {price}")
        .build()
    );

    private final StringSetting cancelCommand = sgCommands.add(new StringSetting.Builder()
        .name("cancel-command")
        .description("Command that cancels your listings. Leave empty to skip cancelling.")
        .defaultValue("/bm cancel")
        .build()
    );

    private final IntSetting cancelRepeats = sgCommands.add(new IntSetting.Builder()
        .name("cancel-repeats")
        .description("How many times the cancel command is sent (use more than 1 if the server cancels one listing per command).")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    // ---- Timing ----
    private final DoubleSetting cancelDelay = sgTiming.add(new DoubleSetting.Builder()
        .name("cancel-after-seconds")
        .description("Seconds to wait after posting before cancelling.")
        .defaultValue(30)
        .min(0.5)
        .sliderRange(1, 300)
        .build()
    );

    private final IntSetting commandDelay = sgTiming.add(new IntSetting.Builder()
        .name("command-delay-ticks")
        .description("Ticks between two commands (20 ticks = 1 second). Raise it if the server has a command cooldown.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );

    private final IntSetting swapDelay = sgTiming.add(new IntSetting.Builder()
        .name("swap-delay-ticks")
        .description("Ticks to wait after moving the item to the hotbar, before sending the command.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final DoubleSetting repostDelay = sgTiming.add(new DoubleSetting.Builder()
        .name("repost-delay-seconds")
        .description("Seconds to wait after cancelling before posting again.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 30)
        .build()
    );

    // ---- Loop ----
    private final IntSetting maxListings = sgLoop.add(new IntSetting.Builder()
        .name("max-listings")
        .description("Maximum number of listings per round (0 = post everything that matches).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 45)
        .build()
    );

    private final BoolSetting repost = sgLoop.add(new BoolSetting.Builder()
        .name("repost")
        .description("Post again after cancelling, forever (or until max-rounds is reached).")
        .defaultValue(true)
        .build()
    );

    private final IntSetting maxRounds = sgLoop.add(new IntSetting.Builder()
        .name("max-rounds")
        .description("Stop after this many post/cancel rounds (0 = unlimited).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 100)
        .visible(repost::get)
        .build()
    );

    private State state = State.POST_PREPARE;
    private int timer;
    private int posted;
    private int cancelsSent;
    private int rounds;
    private String currentPrice = "";

    public BlackMarketPoster() {
        super(AddonTemplate.CATEGORY, "black-market-poster", "Posts items on a server's black market, cancels them after a delay and posts them again.");
    }

    @Override
    public void onActivate() {
        state = State.POST_PREPARE;
        timer = 0;
        posted = 0;
        cancelsSent = 0;
        rounds = 0;
        currentPrice = defaultPrice.get();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        switch (state) {
            case POST_PREPARE -> prepare();
            case POST_SEND -> sendPost();
            case WAITING -> {
                state = State.CANCELLING;
                cancelsSent = 0;
            }
            case CANCELLING -> sendCancel();
            case COOLDOWN -> afterCooldown();
        }
    }

    // Step 1: find an item, put it in the chosen hotbar slot and select it.
    private void prepare() {
        if (maxListings.get() > 0 && posted >= maxListings.get()) {
            beginWait();
            return;
        }

        // Remember the display name of the FIRST matching stack (same one InvUtils.find returns).
        final String[] firstName = {null};
        FindItemResult result = InvUtils.find(stack -> {
            if (stack.isEmpty() || !items.get().contains(stack.getItem())) return false;
            if (firstName[0] == null) firstName[0] = Names.get(stack.getItem());
            return true;
        });

        if (!result.found()) {
            if (posted > 0) {
                beginWait();
            } else {
                warning("No items from the list found in your inventory. Turning off.");
                toggle();
            }
            return;
        }

        int target = hotbarSlot.get() - 1;
        if (result.isHotbar()) {
            InvUtils.swap(result.slot(), false);
        } else {
            InvUtils.move().from(result.slot()).toHotbar(target);
            InvUtils.swap(target, false);
        }

        currentPrice = priceFor(firstName[0]);
        timer = swapDelay.get();
        state = State.POST_SEND;
    }

    // Step 2: run the post command for the item we are holding.
    private void sendPost() {
        String command = postCommand.get()
            .replace("{price}", currentPrice)
            .replace("{amount}", String.valueOf(amount.get()));

        sendCommand(command);
        posted++;

        timer = commandDelay.get();
        state = State.POST_PREPARE;
    }

    private void beginWait() {
        info("Posted %d listing(s). Cancelling in %.1f s.", posted, cancelDelay.get());
        state = State.WAITING;
        timer = (int) Math.round(cancelDelay.get() * 20);
    }

    private void sendCancel() {
        String command = cancelCommand.get().trim();

        if (!command.isEmpty()) {
            sendCommand(command);
            cancelsSent++;

            if (cancelsSent < cancelRepeats.get()) {
                timer = commandDelay.get();
                return;
            }
        }

        rounds++;
        state = State.COOLDOWN;
        timer = (int) Math.round(repostDelay.get() * 20);
    }

    private void afterCooldown() {
        if (!repost.get() || (maxRounds.get() > 0 && rounds >= maxRounds.get())) {
            info("Finished %d round(s). Turning off.", rounds);
            toggle();
            return;
        }

        posted = 0;
        state = State.POST_PREPARE;
    }

    private void sendCommand(String command) {
        command = command.trim();
        if (command.isEmpty()) return;
        if (!command.startsWith("/")) command = "/" + command;
        ChatUtils.sendPlayerMsg(command);
    }

    private String priceFor(String itemName) {
        if (itemName != null) {
            for (String line : priceOverrides.get()) {
                int eq = line.lastIndexOf('=');
                if (eq <= 0) continue;

                String name = line.substring(0, eq).trim();
                String price = line.substring(eq + 1).trim();

                if (!price.isEmpty() && name.equalsIgnoreCase(itemName)) return price;
            }
        }

        return defaultPrice.get();
    }

    @Override
    public String getInfoString() {
        return state.name().toLowerCase().replace('_', ' ');
    }
}
