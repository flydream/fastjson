package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.JSONScanner;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.util.ASMClassLoader;
import com.alibaba.fastjson.util.TypeUtils;

public class DefaultObjectDeserializer implements ObjectDeserializer {

    public DefaultObjectDeserializer(){
    }

    public void parseMap(DefaultJSONParser parser, Map<Object, Object> map, Type keyType, Type valueType, Object fieldName) {
        JSONScanner lexer = (JSONScanner) parser.getLexer();

        if (lexer.token() != JSONToken.LBRACE) {
            throw new JSONException("syntax error, expect {, actual " + lexer.token());
        }

        ObjectDeserializer keyDeserializer = parser.getConfig().getDeserializer(keyType);
        ObjectDeserializer valueDeserializer = parser.getConfig().getDeserializer(valueType);
        lexer.nextToken(keyDeserializer.getFastMatchToken());

        for (;;) {
            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken(JSONToken.COMMA);
                break;
            }
            
            Object key = keyDeserializer.deserialze(parser, keyType, null);
            
            if (lexer.token() != JSONToken.COLON) {
                throw new JSONException("syntax error, expect :, actual " + lexer.token());
            }
            
            lexer.nextToken(valueDeserializer.getFastMatchToken());
            Object value = valueDeserializer.deserialze(parser, valueType, key);

            map.put(key, value);
            
            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(keyDeserializer.getFastMatchToken());
            }
        }
    }

    @SuppressWarnings("rawtypes")
    public Map parseMap(DefaultJSONParser parser, Map<String, Object> map, Type valueType, Object fieldName) {
        JSONScanner lexer = (JSONScanner) parser.getLexer();

        if (lexer.token() != JSONToken.LBRACE) {
            throw new JSONException("syntax error, expect {, actual " + lexer.token());
        }

        for (;;) {
            lexer.skipWhitespace();
            char ch = lexer.getCurrent();
            if (parser.isEnabled(Feature.AllowArbitraryCommas)) {
                while (ch == ',') {
                    lexer.incrementBufferPosition();
                    lexer.skipWhitespace();
                    ch = lexer.getCurrent();
                }
            }

            String key;
            if (ch == '"') {
                key = lexer.scanSymbol(parser.getSymbolTable(), '"');
                lexer.skipWhitespace();
                ch = lexer.getCurrent();
                if (ch != ':') {
                    throw new JSONException("expect ':' at " + lexer.pos());
                }
            } else if (ch == '}') {
                lexer.incrementBufferPosition();
                lexer.resetStringPosition();
                return map;
            } else if (ch == '\'') {
                if (!parser.isEnabled(Feature.AllowSingleQuotes)) {
                    throw new JSONException("syntax error");
                }

                key = lexer.scanSymbol(parser.getSymbolTable(), '\'');
                lexer.skipWhitespace();
                ch = lexer.getCurrent();
                if (ch != ':') {
                    throw new JSONException("expect ':' at " + lexer.pos());
                }
            } else {
                if (!parser.isEnabled(Feature.AllowUnQuotedFieldNames)) {
                    throw new JSONException("syntax error");
                }

                key = lexer.scanSymbolUnQuoted(parser.getSymbolTable());
                lexer.skipWhitespace();
                ch = lexer.getCurrent();
                if (ch != ':') {
                    throw new JSONException("expect ':' at " + lexer.pos() + ", actual " + ch);
                }
            }

            lexer.incrementBufferPosition();
            lexer.skipWhitespace();
            ch = lexer.getCurrent();

            lexer.resetStringPosition();
            
            if (key == "@type") {
                String typeName = lexer.scanSymbol(parser.getSymbolTable(), '"');
                Class<?> clazz = TypeUtils.loadClass(typeName);
                
                if (clazz == map.getClass()) {
                    lexer.nextToken(JSONToken.COMMA);
                    continue;
                }

                ObjectDeserializer deserializer = parser.getConfig().getDeserializer(clazz);

                lexer.nextToken(JSONToken.COMMA);

                parser.setResolveStatus(DefaultJSONParser.TypeNameRedirect);
                return (Map) deserializer.deserialze(parser, clazz, fieldName);
            }

            Object value;
            lexer.nextToken();

            if (lexer.token() == JSONToken.NULL) {
                value = null;
                lexer.nextToken();
            } else {
                value = parser.parseObject(valueType);
            }

            map.put(key, value);

            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken();
                return map;
            }
        }
        
    }

    public void parseObject(DefaultJSONParser parser, Object object) {
        Class<?> clazz = object.getClass();
        Map<String, FieldDeserializer> setters = parser.getConfig().getFieldDeserializers(clazz);

        JSONScanner lexer = (JSONScanner) parser.getLexer(); // xxx

        if (lexer.token() != JSONToken.LBRACE) {
            throw new JSONException("syntax error, expect {, actual " + lexer.token());
        }

        final Object[] args = new Object[1];

        for (;;) {
            // lexer.scanSymbol
            String key = lexer.scanSymbol(parser.getSymbolTable());

            if (key == null) {
                if (lexer.token() == JSONToken.RBRACE) {
                    lexer.nextToken(JSONToken.COMMA);
                    break;
                }
                if (lexer.token() == JSONToken.COMMA) {
                    if (parser.isEnabled(Feature.AllowArbitraryCommas)) {
                        continue;
                    }
                }
            }

            FieldDeserializer fieldDeser = setters.get(key);
            if (fieldDeser == null) {
                if (!parser.isEnabled(Feature.IgnoreNotMatch)) {
                    throw new JSONException("setter not found, class " + clazz.getName() + ", property " + key);
                }

                lexer.nextTokenWithColon();
                parser.parse(); // skip

                if (lexer.token() == JSONToken.RBRACE) {
                    lexer.nextToken();
                    return;
                }

                continue;
            } else {
                Method method = fieldDeser.getMethod();
                Class<?> fieldClass = method.getParameterTypes()[0];
                Type fieldType = method.getGenericParameterTypes()[0];
                if (fieldClass == int.class) {
                    lexer.nextTokenWithColon(JSONToken.LITERAL_INT);
                    args[0] = IntegerDeserializer.deserialze(parser);
                } else if (fieldClass == String.class) {
                    lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);
                    args[0] = StringDeserializer.deserialze(parser);
                } else if (fieldClass == long.class) {
                    lexer.nextTokenWithColon(JSONToken.LITERAL_INT);
                    args[0] = LongDeserializer.deserialze(parser);
                } else if (fieldClass == List.class) {
                    lexer.nextTokenWithColon(JSONToken.LBRACE);
                    args[0] = CollectionDeserializer.instance.deserialze(parser, fieldType, null);
                } else {
                    ObjectDeserializer fieldValueDeserializer = parser.getConfig().getDeserializer(fieldClass,
                                                                                                   fieldType);

                    lexer.nextTokenWithColon(fieldValueDeserializer.getFastMatchToken());
                    args[0] = fieldValueDeserializer.deserialze(parser, fieldType, null);
                }

                try {
                    method.invoke(object, args);
                } catch (Exception e) {
                    throw new JSONException("set proprety error, " + method.getName(), e);
                }
            }

            if (lexer.token() == JSONToken.COMMA) {
                continue;
            }

            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken(JSONToken.COMMA);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        if (type instanceof Class<?>) {
            return deserialze(parser, (Class<T>) type);
        }

        if (type instanceof ParameterizedType) {
            return (T) deserialze(parser, (ParameterizedType) type, fieldName);
        }

        if (type instanceof TypeVariable) {
            return (T) parser.parse(fieldName);
        }

        if (type instanceof WildcardType) {
            return (T) parser.parse(fieldName);
        }

        throw new JSONException("not support type : " + type);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public <T> T deserialze(DefaultJSONParser parser, ParameterizedType type, Object fieldName) {
        try {
            Type rawType = type.getRawType();
            if (rawType instanceof Class<?>) {
                Class<?> rawClass = (Class<?>) rawType;

                if (Map.class.isAssignableFrom(rawClass)) {
                    Map map;

                    if (Modifier.isAbstract(rawClass.getModifiers())) {
                        if (rawClass == Map.class) {
                            map = new HashMap();
                        } else if (rawClass == SortedMap.class) {
                            map = new TreeMap();
                        } else if (rawClass == ConcurrentMap.class) {
                            map = new ConcurrentHashMap();
                        } else {
                            throw new JSONException("can not create instance : " + rawClass);
                        }
                    } else {
                        if (rawClass == HashMap.class) {
                            map = new HashMap();
                        } else {
                            map = (Map) rawClass.newInstance();
                        }
                    }

                    Type keyType = type.getActualTypeArguments()[0];
                    Type valueType = type.getActualTypeArguments()[1];

                    if (keyType == String.class) {
                        map = parseMap(parser, map, valueType, fieldName);
                    } else {
                        parseMap(parser, map, keyType, valueType, fieldName);
                    }

                    return (T) map;
                }

            }

            throw new JSONException("not support type : " + type);
        } catch (JSONException e) {
            throw e;
        } catch (Throwable e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public <T> T deserialze(DefaultJSONParser parser, Class<T> clazz) {
        Object value = null;
        if (clazz.isAssignableFrom(HashMap.class)) {
            value = new HashMap();
        } else if (clazz.isAssignableFrom(TreeMap.class)) {
            value = new TreeMap();
        } else if (clazz.isAssignableFrom(ConcurrentHashMap.class)) {
            value = new ConcurrentHashMap();
        } else if (clazz.isAssignableFrom(Properties.class)) {
            value = new Properties();
        }

        if (clazz == Class.class) {
            Object classValue = parser.parse();
            if (classValue == null) {
                return null;
            }

            if (classValue instanceof String) {
                return (T) ASMClassLoader.forName((String) classValue);
            }
        }

        try {
            parseObject(parser, value);
            return (T) value;
        } catch (JSONException e) {
            throw e;
        } catch (Throwable e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }
}
