package com.findback.app;

import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * Open-vocabulary object finder: MobileCLIP turns any phrase into an embedding, and YOLOE-11s finds
 * that phrase in camera frames. Fully on-device. See scripts/export-finder.py for the model export.
 */
public final class OpenVocabFinder implements AutoCloseable {
    static final String DETECTOR="finder_yoloe11s.onnx", TEXT="finder_text.onnx", VOCAB="clip_bpe_vocab.bin"; // gzip bytes; a .gz name would be decompressed by the build;
    private static final int SIZE=640;
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession detector, text;
    private final ClipTokenizer tokenizer;
    private final int[] pixels=new int[SIZE*SIZE];
    private final float[] input=new float[3*SIZE*SIZE];
    private final Bitmap scaled=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
    private final Canvas canvas=new Canvas(scaled);
    private final Paint resize=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF target=new RectF();

    /** A prepared search: one or more phrases encoded once, reused for every frame. */
    public record Query(String[] phrases,float[][] features) {}

    public static boolean bundled(Context c){
        try{for(String name:new String[]{DETECTOR,TEXT,VOCAB})c.getAssets().open(name).close();return true;}catch(IOException e){return false;}
    }

    public OpenVocabFinder(Context c) throws Exception {
        tokenizer=new ClipTokenizer(c.getAssets().open(VOCAB));
        detector=session(c,DETECTOR,true);
        text=session(c,TEXT,false);
    }

    /** ORT needs a file path to avoid holding ~100 MB of model bytes on the Java heap. */
    private OrtSession session(Context c,String asset,boolean vision) throws Exception {
        File dir=new File(c.getNoBackupFilesDir(),"finder");dir.mkdirs();
        File file=new File(dir,asset);
        long expected;
        try(var fd=c.getAssets().openFd(asset)){expected=fd.getLength();}catch(IOException compressed){expected=-1;}
        if(!file.isFile()||(expected>0&&file.length()!=expected)){
            File partial=new File(dir,asset+".partial");
            try(InputStream in=c.getAssets().open(asset);OutputStream out=new FileOutputStream(partial)){byte[] buf=new byte[1<<16];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}
            if(!partial.renameTo(file))throw new IOException("Could not install "+asset);
        }
        OrtSession.SessionOptions options=new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(vision?4:2);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if(vision)try{options.addXnnpack(Map.of("intra_op_num_threads","4"));}catch(OrtException unavailable){android.util.Log.w("Saathi","XNNPACK unavailable; using default CPU",unavailable);}
        return env.createSession(file.getAbsolutePath(),options);
    }

    public Query prepare(String... phrases) throws OrtException {
        int n=phrases.length;
        int[] tokens=new int[n*ClipTokenizer.CONTEXT];
        for(int i=0;i<n;i++)System.arraycopy(tokenizer.tokenize(phrases[i]),0,tokens,i*ClipTokenizer.CONTEXT,ClipTokenizer.CONTEXT);
        try(OnnxTensor t=OnnxTensor.createTensor(env,IntBuffer.wrap(tokens),new long[]{n,ClipTokenizer.CONTEXT});
            OrtSession.Result r=text.run(Collections.singletonMap("tokens",t))){
            return new Query(phrases,(float[][])r.get(0).getValue());
        }
    }

    /** Best box per phrase above {@code threshold}, coordinates normalised to the frame. */
    public List<YoloDetector.Hit> detect(Bitmap image,Query query,float threshold) throws OrtException {
        float scale=Math.min((float)SIZE/image.getWidth(),(float)SIZE/image.getHeight());
        float padX=(SIZE-image.getWidth()*scale)/2,padY=(SIZE-image.getHeight()*scale)/2;
        canvas.drawColor(Color.rgb(114,114,114));
        target.set(padX,padY,padX+image.getWidth()*scale,padY+image.getHeight()*scale);
        canvas.drawBitmap(image,null,target,resize);
        scaled.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
        int plane=pixels.length;
        for(int i=0;i<plane;i++){int p=pixels[i];input[i]=((p>>16)&255)/255f;input[i+plane]=((p>>8)&255)/255f;input[i+2*plane]=(p&255)/255f;}
        int n=query.phrases().length;
        float[] flat=new float[n*512];
        for(int i=0;i<n;i++)System.arraycopy(query.features()[i],0,flat,i*512,512);
        Map<String,OnnxTensor> inputs=new HashMap<>();
        try(OnnxTensor img=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),new long[]{1,3,SIZE,SIZE});
            OnnxTensor txt=OnnxTensor.createTensor(env,FloatBuffer.wrap(flat),new long[]{1,n,512})){
            inputs.put("images",img);inputs.put("text",txt);
            try(OrtSession.Result r=detector.run(inputs)){
                float[][] out=((float[][][])r.get(0).getValue())[0];
                List<YoloDetector.Hit> hits=new ArrayList<>();
                for(int c=0;c<n;c++){
                    float[] scores=out[4+c];int best=-1;float top=threshold;
                    for(int a=0;a<scores.length;a++)if(scores[a]>=top){top=scores[a];best=a;}
                    if(best>=0)hits.add(new YoloDetector.Hit(query.phrases()[c],top,
                        (out[0][best]-padX)/(scale*image.getWidth()),(out[1][best]-padY)/(scale*image.getHeight()),
                        out[2][best]/(scale*image.getWidth()),out[3][best]/(scale*image.getHeight())));
                }
                hits.sort((a,b)->Float.compare(b.score(),a.score()));
                return hits;
            }
        }
    }

    /** Common spoken names that the text encoder knows better under a second phrase. */
    public static String[] phrasesFor(String phrase){
        String p=phrase.toLowerCase(Locale.ROOT).trim();
        LinkedHashSet<String> out=new LinkedHashSet<>();out.add(p);
        String[][] alt={{"earpods","earbuds"},{"airpods","earbuds"},{"ear pods","earbuds"},{"specs","glasses"},{"spectacles","glasses"},
            {"mobile","phone"},{"cellphone","phone"},{"purse","wallet"},{"tablet strip","medicine"},{"charger","phone charger"},{"remote","remote control"}};
        for(String[] a:alt)if(p.contains(a[0])&&!p.contains(a[1]))out.add(p.replace(a[0],a[1]).trim());
        return out.toArray(new String[0]);
    }

    @Override public void close() throws OrtException {detector.close();text.close();scaled.recycle();}
}
