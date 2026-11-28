package kr.snu.youngmini.desktop

import org.json.JSONObject
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Deterministic academic plots. Numeric inputs and labels only; no executable expressions. */
object Charts {
    fun render(spec:JSONObject,target:File) {
        val kind=spec.optString("type","line");require(kind in listOf("line","bar","scatter"))
        val series=spec.getJSONArray("series");require(series.length() in 1..8)
        val points=(0 until series.length()).map {i->val values=series.getJSONObject(i).getJSONArray("values");require(values.length() in 1..2000);(0 until values.length()).map {j->val p=values.optJSONArray(j);val x=p?.getDouble(0)?:j.toDouble();val y=p?.getDouble(1)?:values.getDouble(j);require(x.isFinite()&&y.isFinite());x to y}}
        val flat=points.flatten();var xmin=flat.minOf {it.first};var xmax=flat.maxOf {it.first};var ymin=flat.minOf {it.second};var ymax=flat.maxOf {it.second}
        if(kind=="bar"){ymin=minOf(0.0,ymin);ymax=maxOf(0.0,ymax);xmin-=0.6;xmax+=0.6}
        if(xmin==xmax){xmin-=0.5;xmax+=0.5};if(ymin==ymax){ymin-=0.5;ymax+=0.5};val pad=(ymax-ymin)*0.06;ymax+=pad;ymin-=pad
        fun x(n:Double)=110+(n-xmin)/(xmax-xmin)*970
        fun y(n:Double)=620-(n-ymin)/(ymax-ymin)*480
        fun short(s:String,max:Int)=s.take(max)
        fun label(n:Double)=if(kotlin.math.abs(n)>=1e5||(n!=0.0&&kotlin.math.abs(n)<0.001))"%.2e".format(java.util.Locale.ROOT,n)else "%.3f".format(java.util.Locale.ROOT,n).trimEnd('0').trimEnd('.')
        val image=BufferedImage(1200,800,BufferedImage.TYPE_INT_RGB);val g=image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.color=Color.WHITE;g.fillRect(0,0,1200,800)
            g.font=Font("Malgun Gothic",Font.BOLD,28);g.color=Color(36,54,79);g.drawString(short(spec.optString("title"),60),90,65)
            g.font=Font("Malgun Gothic",Font.PLAIN,15)
            for(i in 0..5){val v=ymin+(ymax-ymin)*i/5;val yy=y(v).toInt();g.color=Color(230,234,240);g.drawLine(110,yy,1080,yy);g.color=Color(95,110,130);g.drawString(label(v),12,yy+5)}
            val labels=spec.optJSONArray("labels")
            for(i in 0..5){val value=xmin+(xmax-xmin)*i/5;val xx=x(value).toInt();g.color=Color(95,110,130);g.drawString(label(value),xx-15,650)}
            if(labels!=null&&labels.length()<=16){for(i in 0 until labels.length()){g.color=Color.WHITE;g.fillRect(x(i.toDouble()).toInt()-25,633,65,26);g.color=Color(95,110,130);g.drawString(short(labels.getString(i),12),x(i.toDouble()).toInt()-20,650)}}
            val colors=listOf(Color(46,103,188),Color(222,132,51),Color(44,145,117),Color(150,93,176),Color(193,83,99),Color(66,147,170),Color(130,133,57),Color(99,110,130))
            for((index,ps) in points.withIndex()) {
                g.color=colors[index];g.stroke=BasicStroke(3f)
                if(kind=="line"){val line=Path2D.Double();ps.forEachIndexed {i,p->if(i==0)line.moveTo(x(p.first),y(p.second))else line.lineTo(x(p.first),y(p.second))};g.draw(line)}
                for(p in ps)if(kind=="bar"){val width=(850.0/(points.maxOf {it.size}.coerceAtLeast(1)*points.size)).coerceIn(2.0,65.0);val xx=x(p.first)+(index-(points.size-1)/2.0)*width;val y0=y(0.0);g.fill(java.awt.geom.Rectangle2D.Double(xx-width/2,minOf(y0,y(p.second)),width*0.88,kotlin.math.abs(y0-y(p.second))))}else g.fillOval(x(p.first).toInt()-4,y(p.second).toInt()-4,8,8)
                g.fillRect(110+index%4*250,716+index/4*28,18,4);g.font=Font("Malgun Gothic",Font.PLAIN,15);g.drawString(short(series.getJSONObject(index).optString("name","계열 ${index+1}"),20),137+index%4*250,724+index/4*28)
            }
            g.color=Color(95,110,130);g.font=Font("Malgun Gothic",Font.PLAIN,18);g.drawString(short(spec.optString("x_label"),80),500,690);g.drawString(short(spec.optString("y_label"),80),110,118)
            g.font=Font("Malgun Gothic",Font.PLAIN,13);g.drawString(short(spec.optString("source"),140),90,785)
            ImageIO.write(image,"png",target)
        }finally{g.dispose();image.flush()}
    }
}
