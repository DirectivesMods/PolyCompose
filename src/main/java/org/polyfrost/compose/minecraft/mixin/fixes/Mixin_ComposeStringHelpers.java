package org.polyfrost.compose.minecraft.mixin.fixes;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.text.BreakIterator;

@Mixin(targets = "androidx.compose.foundation.text.StringHelpers_skikoKt", remap = false)
public class Mixin_ComposeStringHelpers {
    @Shadow
    private static boolean isWhitespace(int codePoint) {
        throw new AssertionError();
    }

    private static BreakIterator polycompose$chars(String text) {
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        return it;
    }

    @WrapMethod(method = "findPrecedingBreak")
    private static int polycompose$findPrecedingBreak(String text, int index, Operation<Integer> original) {
        return polycompose$chars(text).preceding(index);
    }

    @WrapMethod(method = "findFollowingBreak")
    private static int polycompose$findFollowingBreak(String text, int index, Operation<Integer> original) {
        return polycompose$chars(text).following(index);
    }

    @WrapMethod(method = "canBeEmojiOrPictographic")
    private static boolean polycompose$canBeEmojiOrPictographic(String text, Operation<Boolean> original) {
        return text.codePoints().anyMatch(cp -> Character.isEmoji(cp) || Character.isExtendedPictographic(cp) || cp == 0xFE0F);
    }

    @WrapMethod(method = "findNextNonWhitespaceSymbolsSubsequenceStartOffset")
    private static int polycompose$findNextNonWhitespaceSymbolsSubsequenceStartOffset(int offset, String text, Operation<Integer> original) {
        BreakIterator it = polycompose$chars(text);
        int next = it.next();
        while (next < offset && next != BreakIterator.DONE) next = it.next();
        int current = next;
        next = it.next();
        while (next != BreakIterator.DONE && next < text.length()) {
            if (isWhitespace(text.codePointAt(current)) && !isWhitespace(text.codePointAt(next))) return next;
            current = next;
            next = it.next();
        }
        return current;
    }

    @WrapMethod(method = "midpointPositionWithUnicodeSymbols")
    private static int polycompose$midpointPositionWithUnicodeSymbols(String text, Operation<Integer> original) {
        int half = (int) text.codePoints().count() / 2;
        BreakIterator it = polycompose$chars(text);
        int pos = 0;
        for (int i = 0; i <= half; i++) pos = it.next();
        return pos;
    }
}
