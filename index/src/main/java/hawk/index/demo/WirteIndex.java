package hawk.index.demo;

import hawk.index.core.document.Document;
import hawk.index.core.field.Field;
import hawk.index.core.field.FieldType;
import hawk.index.core.field.StringField;

public class WirteIndex {

    public static void main(String[] args) {
        Document doc = new Document();
        FieldType fieldType = new FieldType(Field.Stored.YES,Field.Tokenized.YES);
        StringField field = new StringField("title", "可爱", fieldType);
        doc.add(field);




    }
}
