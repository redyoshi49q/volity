package Volity::Bookkeeper;

############################################################################
# LICENSE INFORMATION - PLEASE READ
############################################################################
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.
#
# This library is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with this library; if not, write to the Free Software
# Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
############################################################################

use warnings;
use strict;

use base qw(Volity::Jabber);

our $VERSION = '0.2';
use DBIx::Abstract;
use Carp qw(carp croak);

use Volity::GameRecord;
use Volity::Info::Server;
use Volity::Info::Game;
use Volity::Info::Player;

use POE qw(
	   Wheel::SocketFactory
	   Wheel::ReadWrite
	   Filter::Line
	   Driver::SysRW
	   Component::Jabber;
	  );

####################
# Jabber event handlers
####################

# handle_rpc_request: Since this module will define a wide variety of RPC
# methods, instead of elsif-ing through a long list of possible request
# names, we will call only methods that begin with "_rpc_". This offers pretty
# good security, I think.
sub handle_rpc_request {
  my $self = shift;
  my ($rpc_info) = @_;
  my $method = "_rpc_" . $$rpc_info{method};
  if ($self->can($method)) {
    $self->$method($$rpc_info{from}, $$rpc_info{id}, @{$$rpc_info{args}});
  } else {
    warn "I received a $$rpc_info{method} RPC request from $$rpc_info{from}, but I don't know what to do about it.\n";
  }
}

####################
# RPC methods
####################

# For security reasons, all of these methods' names must start with "_rpc_".
# All these methods receive the following arguments:
# ($sender_jid, $rpc_id_attribute, @rpc_arguments)

####
# DB-Writing methods
####

# record_game: (That's 'record' as a verb, here.) Accept a game record and
# a signature as args. Use the sig to confirm that the record came from
# the server, and then store the record in the DB.
sub _rpc_record_game {
  my $self = shift;
  my ($sender_jid, $rpc_id_attr, $game_record_hashref) = @_;
  my $game_record = Volity::GameRecord->new_from_hashref($game_record_hashref->value);
  unless (defined($game_record)) {
    warn "Got bad game struct from $sender_jid.\n";
    # XXX Error response here.
    return;
  }

  # Verify the signature!
  unless ($game_record->verify) {
    warn "Uh oh... signature on game record doesn't seem to verify!!\n";
    # XXX Error response here.
    return;
  }

  # Looks good. Store it.
  $self->store_record_in_db($game_record);
  
}

sub store_record_in_db {
  my $self = shift;
  my ($game_record) = @_;
  my ($server) = Volity::Info::Server->search({jid=>$game_record->server});
  unless ($server) {
      warn "Bizarre... got a record with server JID " . $game_record->server . ", but couldn't get a server object from the DB from it. No record stored.";
  }
  my ($ruleset) = Volity::Info::Ruleset->search({uri=>$game_record->game_uri});
  unless ($ruleset) {
      warn "Bizarre... got a record with server JID " . $game_record->game_uri . ", but couldn't get a server object from the DB from it. No record stored.";
  }
  my $game;			# Volity::Info::Game object
  if (defined($game_record->id)) {
    $game = Volity::Info::Game->retrieve($self->id);
    # XXX Confirm that the record's owner is legit.
    # XXX Do other magic here to update the values.
    # XXX This is all kinds of not implemented yet.
  } else {
    $game = Volity::Info::Game->create({start_time=>$game_record->start_time,
					end_time=>$game_record->end_time,
					server_id=>$server->id,
					ruleset_id=>$ruleset->id,
					signature=>$game_record->signature,
				       });
					
    $game_record->id($game->id);
  }
  # Now go through the player lists. It's always a case of drop-and-insert,
  # for they're all many-to-many linking tables.
  foreach my $player_list (qw(quitters)) {
    my $class = "Volity::Info::Game" . ucfirst(substr($player_list, 0, length($player_list) - 1));
    foreach ($class->search({game_id=>$game->id})) {
      $_->delete;
    }
    my @players = map(Volity::Info::Player->search({jid=>$_}),
		      $game_record->$player_list);
    for my $player (@players) {
      $class->create({game_id=>$game->id, player_id=>$player->id});
    }
  }
  
  # The winners list is a special case, since we must also record how
  # each player placed.
#  # First, clear any old win-records this game may already have.
#  foreach (Volity::Info::GamePlayer->search({game_id=>$game->id})) {
#    $_->delete;
#  }
  # Now record how each player placed.
  my $current_place = 1;	# Start with first place, work down.
  for my $place ($game_record->winners) {
    my @player_jids = ref($place)? @$place : ($place);
    for my $player_jid (@player_jids) {
      # Get this player's DB object, since we need its ID.
      my ($player) = Volity::Info::Player->search({jid=>$player_jid});
      # Record how this player placed!
      my $game_player = Volity::Info::GamePlayer->
	  find_or_create({
			  game_id=>$game->id,
			  player_id=>$player->id,
			 });
      $game_player->place($current_place);
      # XXX Put ranking figuring-out here.
      $game_player->update;
    }
    $current_place++;
  }

  return $game;
}

sub _rpc_set_my_attitude_toward_player {
  my $self = shift;
  my ($sender_jid, $id, $target_jid, $rank) = @_;
  $rank = int($rank);
  if ($rank < -1 or $rank > 1) {
    # XXX Error!
    return;
  }
  my $dbh = $self->dbh;
  $dbh->delete('PLAYER_ATTITUDE', {FROM_JID=>$sender_jid, TO_JID=>$target_jid});
  $dbh->insert('PLAYER_ATTITUDE', {FROM_JID=>$sender_jid, TO_JID=>$target_jid,
				   ATTITUDE=>$rank});
}

sub _rpc_get_my_attitude_toward_player {
  my $self = shift;
  my ($sender, $id, $target) = @_;
  my $dbh = $self->dbh;
  $dbh->select('ATTITUDE', 'PLAYER_ATTITUDE', {FROM_JID=>$sender,
					       TO_JID=>$target});
  my ($att) = $dbh->fetchrow_array;
  $self->send_rpc_response($sender, $id, $att);
}

# This method returns three scalars, with counts of -1, 0, and 1.
sub _rpc_get_all_attitudes_toward_player {
  my $self = shift;
  my ($sender, $id, $target) = @_;
  my $dbh = $self->dbh;
  $dbh->select('ATTITUDE', 'PLAYER_ATTITUDE', {FROM_JID=>$sender,
					       TO_JID=>$target});
  my @atts = (0, 0, 0);
  while (my ($att) = $dbh->fetchrow_array) {
    $atts[$att + 1]++;
  }

  $self->send_rpc_response($sender, $id, \@atts);
}

sub _rpc_get_all_game_records_for_player {
  my $self = shift;
  my ($sender_jid, $rpc_id_attr, $player_jid) = @_;
  my @game_records = $self->fetch_game_records_for_player($player_jid);
  $self->send_rpc_response($sender_jid, $rpc_id_attr,
			   [map($_->render_as_hashref, @game_records)]);
}

sub _rpc_get_game_records_for_player_and_game {
  my $self = shift;
  my ($sender_jid, $rpc_id_attr, $player_jid, $game_uri) = @_;
  my @game_records = $self->fetch_game_records_for_player($player_jid, {GAME_URI=>$game_uri});
  $self->send_rpc_response($sender_jid, $rpc_id_attr,
			   [map($_->render_as_hashref, @game_records)]);
}

sub fetch_game_records_for_player {
  my $self = shift;
  my ($player_jid, $where_args) = @_;
  my $dbh = $self->dbh;
  $where_args ||= {};
  $$where_args{'GAME_PLAYER.PLAYER_JID'} = $player_jid;
  $$where_args{'GAME_PLAYER.GAME_ID'} = 'GAME.ID';

  $dbh->select('ID', 'GAME, GAME_PLAYER', $where_args);
  my @game_records;
  while (my($id) = $dbh->fetchrow_array) { 
    push (@game_records, Volity::GameRecord->new({id=>$id}));
  }
  return @game_records;
}

sub _rpc_get_game_records_for_player_and_server {
  my $self = shift;
  my ($sender_jid, $rpc_id_attr, $player_jid, $server_jid) = @_;
  my @game_records = $self->fetch_game_records_for_player($player_jid, {SERVER_JID=>$server_jid});
  $self->send_rpc_response($sender_jid, $rpc_id_attr,
			   [map($_->render_as_hashref, @game_records)]);
}
  
sub _rpc_get_all_totals_for_player {
  my $self = shift;
  my ($sender, $rpc_id, $player_jid) = @_;
  my %totals = $self->fetch_totals_for_player($player_jid);
  $self->send_rpc_response($sender, $rpc_id, \%totals);
}

sub _rpc_get_all_totals_for_player_and_game {
  my $self = shift;
  my ($sender, $rpc_id, $player_jid, $game_uri) = @_;
  my %totals = $self->fetch_totals_for_player($player_jid, {GAME_URI=>$game_uri});
  $self->send_rpc_response($sender, $rpc_id, \%totals);
}

sub _rpc_get_all_totals_for_player_and_server {
  my $self = shift;
  my ($sender, $rpc_id, $player_jid, $server_jid) = @_;
  my %totals = $self->fetch_totals_for_player($player_jid, {SERVER_JID=>$server_jid});
  $self->send_rpc_response($sender, $rpc_id, \%totals);
}
  
sub fetch_totals_for_player {
  my $self = shift;
  my ($player_jid, $where_args) = @_;
  my $dbh = $self->dbh;
  $where_args ||= {};
  $$where_args{'GAME_PLAYER.PLAYER_JID'} = $player_jid;
  $$where_args{'GAME_PLAYER.GAME_ID'} = 'GAME.ID';
  my %totals;

  $dbh->select('COUNT(ID)', 'GAME, GAME_PLAYER', $where_args);
  $totals{played} = ($dbh->fetchrow_array)[0];
  
  delete($$where_args{'GAME_PLAYER.PLAYER_JID'});
  delete($$where_args{'GAME_PLAYER.GAME_ID'});
  $$where_args{'GAME_WINNER.PLAYER_JID'} = $player_jid;
  $$where_args{'GAME_WINNER.GAME_ID'} = 'GAME.ID';

  $dbh->select('COUNT(ID)', 'GAME, GAME_WINNER', $where_args);
  $totals{won} = ($dbh->fetchrow_array)[0];

  delete($$where_args{'GAME_WINNER.PLAYER_JID'});
  delete($$where_args{'GAME_WINNER.GAME_ID'});
  $$where_args{'GAME_QUITTER.PLAYER_JID'} = $player_jid;
  $$where_args{'GAME_QUITTER.GAME_ID'} = 'GAME.ID';

  $dbh->select('COUNT(ID)', 'GAME, GAME_QUITTER', $where_args);
  $totals{quit} = ($dbh->fetchrow_array)[0];

  return %totals;
}

####################
# POE stuff
####################

# start: run the kernel.
sub start {
  my $self = shift;
  $self->kernel->run;
}

sub jabber_authed {
  my $self = $_[OBJECT];
  my $node = $_[ARG0];
  $self->debug("We have authed!\n");
  $self->debug("My jid: " . $self->jid);
  unless ($node->name eq 'handshake') {
    warn $node->to_str;
  }
}

########################
# Disco Stuff (I think??)
########################

# These methods cover stuff you'd find through service discovery (JEP-0030).

# servers_of_game: For a given game URI, return the JIDs of the servers that
# provide it.
sub servers_of_game {
  my $self = shift;
  my $dbh = $self->dbh;
  my ($game_uri) = @_;
  $dbh->select('JID', 'SERVER', {GAME_URI=>$game_uri});
  my @server_jids;
  while (my($jid) = $dbh->fetchrow_array) {
    push (@server_jids, $jid);
  }

  return @server_jids;
}

1;
