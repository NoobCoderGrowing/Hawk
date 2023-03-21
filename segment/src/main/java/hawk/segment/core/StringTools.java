package hawk.segment.core;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class StringTools {

    private String puntuationPath = "data/punctuation.data";

    private Set<String> puntuationSet = new HashSet<String>();

    public StringTools(){
        loadPunctuationSet();
    }

    public void loadPunctuationSet(){

        try {
            URL fileUrl = getClass().getClassLoader().getResource(puntuationPath);
            BufferedReader reader = new BufferedReader(new FileReader(fileUrl.getPath()));
            String line = reader.readLine();
            while(line != null){
                puntuationSet.add(line);
                line = reader.readLine();
            }
            reader.close();
        } catch (FileNotFoundException e) {
            log.error("puntuation file not found");
            System.exit(-1);
        } catch (Exception e) {
            log.error("met error while reading puntuation file");
            System.exit(-1);
        }
    }

    public String normalizeString(String value){
        value = fullToHalfChange(value);
        value = traditionToSimple(value);
        value = value.toLowerCase();
        value = replaceNotWordBySpace(value, ' ');
        value = replacePuncBySpace(value);
        return value;
    }

    public String fullToHalfChange(String value){
        if (value == null || value.length() == 0) {
            return "";
        }
        char[] charArray = value.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            if(charArray[i] == 12288){
                charArray[i] = 32;
                continue;
            }
            if (charArray[i] >= 65281 && charArray[i] <= 65374){
                charArray[i] = (char) (charArray[i] - 65248);
            }
        }
        return new String(charArray);
    }

    public String traditionToSimple(String value){
        value = ChineseUtils.toSimpleField(value);
        return value;
    }

    public String replaceNotWordBySpace(String value, Character replacer){
        char[] charArray = value.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            if(!Character.isLetterOrDigit(charArray[i]) && (charArray[i] != '-' && charArray
            [i] != '.' && !Character.isSpaceChar(charArray[i]))){
                charArray[i] = replacer;
            }
        }
        return new String(charArray);
    }

    public String replacePuncBySpace(String value){
        char[] charArray = value.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            if(puntuationSet.contains(String.valueOf(charArray[i]))){
                charArray[i] = ' ';
            }
        }
        return String.valueOf(charArray);
    }

    // digit = 1, letter = 2, chinese = 0;
    public int charCat(char character){
        if(Character.isDigit(character)){
            return 1;
        }else if(character >= 'a' && character <= 'z'){
            return 2;
        }
        return 0;
    }

    public String splitChineseDigitEnglishByComma(String value){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if(i==0 || value.charAt(i) == '-' || value.charAt(i) == '.'){
                sb.append(value.charAt(i));
            }else{
                char pre = value.charAt(i-1);
                char cur = value.charAt(i);
                boolean isDiffCat = charCat(pre) != charCat(cur);
                boolean isPreBarOrDot = (pre == '_' || pre == '.');
                if(isDiffCat && !isPreBarOrDot){
                    sb.append(",").append(cur);
                }else{
                    sb.append(cur);
                }
            }
        }
        return sb.toString();
    }

    //coleect hanzi pinyin and digit seperately
    //英文默认为是拼音
    public int collectHanPinDigit(String value, HanPinDigSeg hanPinDigSeg, int pos){
        String[] arr = value.split(",");
        for (int i = 0; i < arr.length; i++) {
            Phrase phrase = new Phrase();
            phrase.setValue(arr[i]);
            phrase.setPos(pos);
            pos += arr[i].length();
            if (charCat(arr[i].charAt(0)) == 0){//中文
                hanPinDigSeg.getHanZiList().add(phrase);
            }else if(charCat(arr[i].charAt(0)) == 1){//数字
                hanPinDigSeg.getDigitList().add(phrase);
            }else{
                hanPinDigSeg.getPinYinList().add(phrase);//拼音
            }
        }
        return pos;
    }
}
