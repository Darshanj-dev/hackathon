package com.findback.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

/**
 * OpenAI CLIP byte-level BPE tokenizer (port of clip/simple_tokenizer.py, MIT licence).
 * Turns a search phrase into the 77 token ids the MobileCLIP text encoder expects.
 */
public final class ClipTokenizer {
    public static final int CONTEXT=77, START=49406, END=49407;
    private static final Pattern WORDS=Pattern.compile("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",Pattern.CASE_INSENSITIVE);
    private final Map<String,Integer> encoder=new HashMap<>();
    private final Map<String,Integer> ranks=new HashMap<>();
    private final char[] byteChars=new char[256];
    private final Map<String,String> cache=new HashMap<>();

    public ClipTokenizer(InputStream gzippedVocab) throws IOException {
        List<Integer> bs=new ArrayList<>();
        for(int b='!';b<='~';b++)bs.add(b);
        for(int b=0xA1;b<=0xAC;b++)bs.add(b);
        for(int b=0xAE;b<=0xFF;b++)bs.add(b);
        List<Integer> cs=new ArrayList<>(bs);
        int n=0;
        for(int b=0;b<256;b++)if(!bs.contains(b)){bs.add(b);cs.add(256+n);n++;}
        for(int i=0;i<bs.size();i++)byteChars[bs.get(i)]=(char)(int)cs.get(i);

        List<String> merges=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new GZIPInputStream(gzippedVocab),StandardCharsets.UTF_8))){
            r.readLine();  // version header
            String line;int limit=49152-256-2;
            while(merges.size()<limit&&(line=r.readLine())!=null)merges.add(line);
        }
        List<String> vocab=new ArrayList<>();
        for(int b:bs)vocab.add(String.valueOf(byteChars[b]));
        for(int b:bs)vocab.add(byteChars[b]+"</w>");
        for(String m:merges)vocab.add(m.replace(" ",""));
        vocab.add("<|startoftext|>");vocab.add("<|endoftext|>");
        for(int i=0;i<vocab.size();i++)encoder.put(vocab.get(i),i);
        for(int i=0;i<merges.size();i++)ranks.put(merges.get(i),i);
    }

    /** [START, ...tokens, END] padded with zeros to 77, truncated like clip.tokenize(truncate=True). */
    public int[] tokenize(String text){
        List<Integer> ids=encode(text);
        int[] out=new int[CONTEXT];
        out[0]=START;
        int count=Math.min(ids.size(),CONTEXT-2);
        for(int i=0;i<count;i++)out[i+1]=ids.get(i);
        out[count+1]=END;
        return out;
    }

    List<Integer> encode(String text){
        String clean=text.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        List<Integer> ids=new ArrayList<>();
        Matcher m=WORDS.matcher(clean);
        while(m.find()){
            StringBuilder mapped=new StringBuilder();
            for(byte b:m.group().getBytes(StandardCharsets.UTF_8))mapped.append(byteChars[b&0xFF]);
            for(String piece:bpe(mapped.toString()).split(" ")){Integer id=encoder.get(piece);if(id!=null)ids.add(id);}
        }
        return ids;
    }

    private String bpe(String token){
        String cached=cache.get(token);
        if(cached!=null)return cached;
        List<String> word=new ArrayList<>();
        for(int i=0;i<token.length()-1;i++)word.add(String.valueOf(token.charAt(i)));
        word.add(token.charAt(token.length()-1)+"</w>");
        while(word.size()>1){
            int best=Integer.MAX_VALUE,at=-1;
            for(int i=0;i<word.size()-1;i++){Integer rank=ranks.get(word.get(i)+" "+word.get(i+1));if(rank!=null&&rank<best){best=rank;at=i;}}
            if(at<0)break;
            String first=word.get(at),second=word.get(at+1);
            List<String> merged=new ArrayList<>();
            for(int i=0;i<word.size();){
                if(i<word.size()-1&&word.get(i).equals(first)&&word.get(i+1).equals(second)){merged.add(first+second);i+=2;}
                else{merged.add(word.get(i));i++;}
            }
            word=merged;
        }
        String result=String.join(" ",word);
        cache.put(token,result);
        return result;
    }
}
