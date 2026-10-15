package com.findback.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class FindCheckTest {
    @Test public void foundWithSideAndDetail(){var c=FindCheck.parse("FOUND RIGHT\\nOn the desk, next to the laptop.".replace("\\n","\n"));assertTrue(c.found());assertEquals("right",c.side());assertEquals("On the desk, next to the laptop.",c.detail());}
    @Test public void notFound(){assertFalse(FindCheck.parse("NOT_FOUND").found());assertFalse(FindCheck.parse("not found").found());}
    @Test public void centreSpellings(){assertEquals("centre",FindCheck.parse("FOUND CENTER\nIn front of you.").side());}
    @Test public void sideFromDetailWhenMissing(){assertEquals("left",FindCheck.parse("FOUND\nIt is on the left near the cup.").side());}
    @Test public void unrelatedReplyIsNotAFind(){assertFalse(FindCheck.parse("I see a table and a laptop.").found());}
    @Test public void emptyReply(){assertFalse(FindCheck.parse(null).found());}
}
