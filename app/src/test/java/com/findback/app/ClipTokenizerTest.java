package com.findback.app;
import java.io.FileInputStream;
import java.util.Arrays;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
/** Expected ids come from the reference Python implementation (clip.tokenize). */
public class ClipTokenizerTest {
    private static ClipTokenizer tokenizer;
    @BeforeClass public static void load() throws Exception {tokenizer=new ClipTokenizer(new FileInputStream("src/main/assets/clip_bpe_vocab.bin"));}
    private void check(String text,int... expected){int[] t=tokenizer.tokenize(text);assertArrayEquals(text,expected,Arrays.copyOf(t,expected.length));assertEquals(0,t.length>expected.length?t[expected.length]:0);}
    @Test public void earbudsCase(){check("earbuds case",49406,47807,2068,49407);}
    @Test public void punctuationAndCase(){check("Find my car keys!",49406,1416,607,1615,6355,256,49407);}
    @Test public void mixedCaseWord(){check("white AirPods case",49406,1579,1281,18776,2068,49407);}
    @Test public void hyphenAndDigit(){check("remote-control 2",49406,9687,268,3366,273,49407);}
    @Test public void multiPieceWord(){check("spectacles",49406,7242,840,49407);}
    @Test public void contraction(){check("it's a pill bottle",49406,585,568,320,19226,5392,49407);}
    @Test public void paddedToContext(){assertEquals(ClipTokenizer.CONTEXT,tokenizer.tokenize("cup").length);}
}
