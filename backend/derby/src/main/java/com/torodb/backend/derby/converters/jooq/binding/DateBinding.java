/*
 * MongoWP - ToroDB-poc: Backend Derby
 * Copyright © 2014 8Kdata Technology (www.8kdata.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.torodb.backend.derby.converters.jooq.binding;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Objects;

import org.jooq.Binding;
import org.jooq.BindingGetResultSetContext;
import org.jooq.BindingGetSQLInputContext;
import org.jooq.BindingGetStatementContext;
import org.jooq.BindingRegisterContext;
import org.jooq.BindingSQLContext;
import org.jooq.BindingSetSQLOutputContext;
import org.jooq.BindingSetStatementContext;
import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultDataType;
import org.jooq.util.derby.DerbyDataType;

import com.torodb.backend.converters.jooq.DataTypeForKV;
import com.torodb.backend.converters.jooq.KVValueConverter;
import com.torodb.kvdocument.values.KVValue;

public class DateBinding<T> implements Binding<Date, T> {

    private static final long serialVersionUID = 1L;

    public static <JT, UT extends KVValue<?>> DataTypeForKV<UT> fromKVValue(Class<UT> type, KVValueConverter<Date, JT, UT> converter) {
        return DataTypeForKV.from(new DefaultDataType<Date>(null, Date.class, "DATE"), converter, new DateBinding<UT>(converter));
    }
    
    public static <UT> DataType<UT> fromType(Class<UT> type, Converter<Date, UT> converter) {
        return new DefaultDataType<Date>(null, Date.class, "DATE").asConvertedDataType(new DateBinding<UT>(converter));
    }

    private final Converter<Date, T> converter;
    
    public DateBinding(Converter<Date, T> converter) {
        super();
        this.converter = converter;
    }

    @Override
    public Converter<Date, T> converter() {
        return converter;
    }

    @Override
    public void sql(BindingSQLContext<T> ctx) throws SQLException {
        ctx.render().sql("date(").visit(DSL.val(ctx.convert(converter()).value(), DerbyDataType.VARCHAR)).sql(')');
    }

    @Override
    public void register(BindingRegisterContext<T> ctx) throws SQLException {
        ctx.statement().registerOutParameter(ctx.index(), Types.DATE);
    }

    @Override
    public void set(BindingSetStatementContext<T> ctx) throws SQLException {
        ctx.statement().setString(ctx.index(), Objects.toString(ctx.convert(converter()).value(), null));
    }

    @Override
    public void get(BindingGetResultSetContext<T> ctx) throws SQLException {
        ctx.convert(converter()).value(ctx.resultSet().getDate(ctx.index()));
    }

    @Override
    public void get(BindingGetStatementContext<T> ctx) throws SQLException {
        ctx.convert(converter()).value(ctx.statement().getDate(ctx.index()));
    }

    @Override
    public void set(BindingSetSQLOutputContext<T> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void get(BindingGetSQLInputContext<T> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }
    
}
