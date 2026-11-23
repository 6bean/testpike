package com.sixbean.testpike.api.ci.domain.component;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import org.springframework.stereotype.Component;


@Component
public class CiBadgeSvgRenderer {

    
    public String render(CiBadgeView view) {
        String label = view.statusLabel() + " · " + view.passRatePhrase();
        String color = colorOf(view.status());
        int width = 80 + label.length() * 7;
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"20\" "
                + "role=\"img\" aria-label=\"" + escape(label) + "\">"
                + "<rect width=\"70\" height=\"20\" fill=\"#30363d\"/>"
                + "<rect x=\"70\" width=\"" + (width - 70) + "\" height=\"20\" fill=\"" + color + "\"/>"
                + "<text x=\"35\" y=\"14\" font-family=\"Verdana,Geneva,sans-serif\" font-size=\"10\" "
                + "fill=\"#e6edf3\" text-anchor=\"middle\">testPike</text>"
                + "<text x=\"" + (70 + (width - 70) / 2.0) + "\" y=\"14\" "
                + "font-family=\"Verdana,Geneva,sans-serif\" font-size=\"10\" fill=\"#fff\" "
                + "text-anchor=\"middle\">" + escape(label) + "</text></svg>";
    }

 
    private static String colorOf(int status) {
        return switch (status) {
            case 1 -> "#9e9e9e";
            case 2 -> "#428bca";
            case 3 -> "#e67e22";
            case 4 -> "#428bca";
            case 5 -> "#f0ad4e";
            case 6 -> "#4caf50";
            case 7 -> "#e05d5d";
            case 8 -> "#616161";
            default -> "#9e9e9e";
        };
    }

    
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
