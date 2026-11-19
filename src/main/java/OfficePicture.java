import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import com.github.weisj.jsvg.parser.*;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.ViewBox;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.usermodel.HwmfPicture;

final class OfficePicture {
    static String type(byte[] data, String hint) {
        if (data.length>=44 && data[0]==1 && data[40]==' ' && data[41]=='E' && data[42]=='M' && data[43]=='F') return "EMF";
        if (data.length>=4 && data[0]==(byte)0xd7 && data[1]==(byte)0xcd && data[2]==(byte)0xc6 && data[3]==(byte)0x9a) return "WMF";
        String start=new String(data,0,Math.min(data.length,512),StandardCharsets.UTF_8).replace("\uFEFF","").stripLeading();
        if (start.startsWith("<") && start.contains("<svg")) return "SVG";
        return hint.toUpperCase(Locale.ROOT);
    }

    static BufferedImage decode(byte[] data, String type) throws IOException {
        var in=new ByteArrayInputStream(data);
        switch(type) {
            case "WMF" -> {
                var picture=new HwmfPicture(in);
                return render(picture.getSize(),picture::draw);
            }
            case "EMF" -> {
                var picture=new HemfPicture(in);
                return render(picture.getSize(),picture::draw);
            }
            case "SVG" -> {
                var context=LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL).build();
                var picture=new SVGLoader().load(in,null,context);
                if(picture==null) throw new IOException("SVG 해석 실패");
                return render(picture.size(),(g,bounds)->picture.render(null,g,new ViewBox(bounds)));
            }
            default -> { return ImageIO.read(in); }
        }
    }

    private static BufferedImage render(Dimension2D size, BiConsumer<Graphics2D,Rectangle2D> paint) throws IOException {
        double w=Math.abs(size.getWidth()), h=Math.abs(size.getHeight());
        if(!(w>0 && h>0 && Double.isFinite(w) && Double.isFinite(h))) throw new IOException("그림 크기 오류");
        double scale=Math.min(3,4000/Math.max(w,h));
        int width=Math.max(1,(int)Math.ceil(w*scale)),height=Math.max(1,(int)Math.ceil(h*scale));
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0,0,width,height);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            paint.accept(g,new Rectangle2D.Double(0,0,width,height));
        } finally { g.dispose(); }
        return image;
    }
}
