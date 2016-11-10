/*
 * MongoWP - ToroDB-poc: MongoDB Repl
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
package com.torodb.mongodb.repl.commands;

import javax.inject.Inject;

import com.eightkdata.mongowp.Status;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.admin.CreateCollectionCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.admin.CreateIndexesCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.admin.DropCollectionCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.admin.DropDatabaseCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.admin.DropIndexesCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.admin.RenameCollectionCommand;
import com.eightkdata.mongowp.server.api.Command;
import com.eightkdata.mongowp.server.api.CommandsExecutor;
import com.eightkdata.mongowp.server.api.Request;
import com.eightkdata.mongowp.server.api.impl.MapBasedCommandsExecutor;
import com.torodb.mongodb.repl.commands.impl.CreateCollectionReplImpl;
import com.torodb.mongodb.repl.commands.impl.CreateIndexesReplImpl;
import com.torodb.mongodb.repl.commands.impl.DropCollectionReplImpl;
import com.torodb.mongodb.repl.commands.impl.DropDatabaseReplImpl;
import com.torodb.mongodb.repl.commands.impl.DropIndexesReplImpl;
import com.torodb.mongodb.repl.commands.impl.LogAndIgnoreReplImpl;
import com.torodb.mongodb.repl.commands.impl.LogAndStopReplImpl;
import com.torodb.mongodb.repl.commands.impl.RenameCollectionReplImpl;
import com.torodb.torod.ExclusiveWriteTorodTransaction;

/**
 *
 */
public final class ReplCommandsExecutor implements CommandsExecutor<ExclusiveWriteTorodTransaction> {

    private final MapBasedCommandsExecutor<ExclusiveWriteTorodTransaction> delegate;

    @Inject
    public ReplCommandsExecutor(ReplCommandsLibrary library, 
            LogAndStopReplImpl logAndStopReplImpl,
            LogAndIgnoreReplImpl logAndIgnoreReplImpl,
            CreateCollectionReplImpl createCollectionReplImpl,
            CreateIndexesReplImpl createIndexesReplImpl,
            DropCollectionReplImpl dropCollectionReplImpl,
            DropDatabaseReplImpl dropDatabaseReplImpl,
            DropIndexesReplImpl dropIndexesReplImpl,
            RenameCollectionReplImpl renameCollectionReplImpl) {
        delegate = MapBasedCommandsExecutor
                .<ExclusiveWriteTorodTransaction>fromLibraryBuilder(library)
                .addImplementation(LogAndStopCommand.INSTANCE, logAndStopReplImpl)
                .addImplementation(LogAndIgnoreCommand.INSTANCE, logAndIgnoreReplImpl)
//                .addImplementation(ApplyOpsCommand.INSTANCE, whatever)
//                .addImplementation(colmod, whatever)
//                .addImplementation(coverToCapped, whatever)
                .addImplementation(CreateCollectionCommand.INSTANCE, createCollectionReplImpl)
                .addImplementation(CreateIndexesCommand.INSTANCE, createIndexesReplImpl)
                .addImplementation(DropCollectionCommand.INSTANCE, dropCollectionReplImpl)
                .addImplementation(DropDatabaseCommand.INSTANCE, dropDatabaseReplImpl)
                .addImplementation(DropIndexesCommand.INSTANCE, dropIndexesReplImpl)
                .addImplementation(RenameCollectionCommand.INSTANCE, renameCollectionReplImpl)
//                .addImplementation(emptycapped, whatever)

                .build();
    }

    @Override
    public <Arg, Result> Status<Result> execute(Request request,
            Command<? super Arg, ? super Result> command, Arg arg,
            ExclusiveWriteTorodTransaction context) {
        return delegate.execute(request, command, arg, context);
    }
}
