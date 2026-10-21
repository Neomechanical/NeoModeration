package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.config.InputLimits;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Map;

/** An API key typed into a command ({@code /nmod key}, {@code /nmod setup}): read and checked in one place. */
public final class ApiKeyArgument {

    private ApiKeyArgument() {
    }

    /**
     * The key typed from {@code args[from]} on, or null once the sender was told why there is none: {@code usage}
     * runs when nothing was typed.
     */
    public static String read(MessageService messages, CommandSender sender, String[] args, int from, Runnable usage) {
        String apiKey = String.join(" ", Arrays.copyOfRange(args, Math.min(from, args.length), args.length)).trim();
        if (apiKey.isEmpty()) {
            usage.run();
            return null;
        }
        if (!InputLimits.isApiKeyLengthValid(apiKey)) {
            messages.send(sender, "error.api-key-too-long", Map.of("max", String.valueOf(InputLimits.MAX_API_KEY_LENGTH)));
            return null;
        }
        return apiKey;
    }
}
