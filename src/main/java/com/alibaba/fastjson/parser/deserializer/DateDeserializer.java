package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONScanner;
import com.alibaba.fastjson.parser.JSONToken;

public class DateDeserializer implements ObjectDeserializer {

    public final static DateDeserializer instance = new DateDeserializer();

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type clazz, Object fieldName) {
        Object val = parser.parse();

        if (val == null) {
            return null;
        }

        if (val instanceof java.util.Date) {
            return (T) val;
        } else if (val instanceof Number) {
            return (T) new java.util.Date(((Number) val).longValue());
        } else if (val instanceof String) {
            String strVal = (String) val;
            if (strVal.length() == 0) {
                return null;
            }
            
            JSONScanner dateLexer = new JSONScanner(strVal);
            if (dateLexer.scanISO8601DateIfMatch()) {
                return (T) dateLexer.getCalendar().getTime();
            }
            
            SimpleDateFormat dateFormat = new SimpleDateFormat(JSON.DEFFAULT_DATE_FORMAT);
            try {
                return (T) dateFormat.parse(strVal);
            } catch (ParseException e) {
                // skip
            }

            long longVal = Long.parseLong(strVal);
            return (T) new java.util.Date(longVal);
        }
        
        throw new JSONException("parse error");
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_INT;
    }
}
