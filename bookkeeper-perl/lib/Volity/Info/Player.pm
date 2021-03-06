package Volity::Info::Player;

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

use base qw(Volity::Info);

Volity::Info::Player->table('player');
Volity::Info::Player->columns(All=>qw(id jid));
Volity::Info::Player->has_many(seats=>["Volity::Info::PlayerSeat" => 'seat_id'], 'player_id');
Volity::Info::Player->has_many(rulesets_owned=>"Volity::Info::Ruleset" => "player_id");
Volity::Info::Player->has_many(parlors_owned=>"Volity::Info::Server" => "player_id");
Volity::Info::Player->has_many(ui_files_owned=>"Volity::Info::File" => "player_id");


# rulesets: Return Volity::Info::Ruleset objects corresponding to rulesets
# that this player has played.
sub rulesets {
    my $self = shift;
    my @rulesets = Volity::Info::Ruleset->search_with_player($self);
    return @rulesets;
}

# bad_attitude_total and good_attitude_total are columns that are set by
# MySQL-side triggers and stored procedures, so we won't define them as
# columns here, but will set up some read-only accessors.
sub bad_attitude_total {
    my $self = shift;
    my $attitude = Volity::Info->db_Main()->selectrow_arrayref("select bad_attitude_total from player where id = $self");
    return $attitude->[0];
}

sub good_attitude_total {
    my $self = shift;
    my $attitude = Volity::Info->db_Main()->selectrow_arrayref("select good_attitude_total from player where id = $self");
    return $attitude->[0];
}

1;
